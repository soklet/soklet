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
import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

enum InternalShutdownCleanupDisposition {
	NOT_CONFIGURED,
	SKIPPED_INCOMPLETE_SHUTDOWN,
	SUCCEEDED,
	FAILED,
	TIMED_OUT
}

enum SokletApplicationPrimaryOutcome {
	EXPECTED,
	STARTUP_FAILURE,
	UNEXPECTED_TERMINATION,
	INCOMPLETE_SHUTDOWN,
	PROCESS_OWNERSHIP_FAILURE
}

enum InternalTerminationAuthority {
	FRAMEWORK_PROVEN,
	TRANSPORT_ATTESTED
}

/** Bounded participant-level facts safe for terminal rendering. */
@Immutable
record SokletApplicationParticipantDiagnostics(
		@NonNull InternalTerminationAuthority authority, int memberCount,
		int failedMembers, int provenMembers, boolean truncated) {
	SokletApplicationParticipantDiagnostics {
		requireNonNull(authority);
		if (memberCount < 0 || failedMembers < 0 || provenMembers < 0)
			throw new IllegalArgumentException(
					"Participant diagnostic counts must be >= 0");
	}
}

/** Core-only diagnostic projection; no transport or observer object escapes. */
@Immutable
final class SokletApplicationCoreDiagnostics {
	@NonNull
	private final LifecycleTransitionSnapshot transitionSnapshot;
	@NonNull
	private final Map<InternalLifecycleComponentType,
			SokletApplicationParticipantDiagnostics> participantDiagnostics;
	@NonNull
	private final InternalLifecyclePolicy lifecyclePolicy;
	private final long lifecycleBeganNanos;

	SokletApplicationCoreDiagnostics(
			@NonNull LifecycleTransitionSnapshot transitionSnapshot,
			@NonNull Map<InternalLifecycleComponentType,
					SokletApplicationParticipantDiagnostics> participantDiagnostics,
			@NonNull InternalLifecyclePolicy lifecyclePolicy,
			long lifecycleBeganNanos) {
		this.transitionSnapshot = requireNonNull(transitionSnapshot);
		EnumMap<InternalLifecycleComponentType,
				SokletApplicationParticipantDiagnostics> copy =
				new EnumMap<>(InternalLifecycleComponentType.class);
		copy.putAll(requireNonNull(participantDiagnostics));
		this.participantDiagnostics = Collections.unmodifiableMap(copy);
		this.lifecyclePolicy = requireNonNull(lifecyclePolicy);
		this.lifecycleBeganNanos = lifecycleBeganNanos;
	}

	@NonNull
	LifecycleTransitionSnapshot transitionSnapshot() {
		return this.transitionSnapshot;
	}

	@NonNull
	Map<InternalLifecycleComponentType, SokletApplicationParticipantDiagnostics>
	participantDiagnostics() {
		return this.participantDiagnostics;
	}

	@NonNull
	InternalLifecyclePolicy lifecyclePolicy() {
		return this.lifecyclePolicy;
	}

	long lifecycleBeganNanos() {
		return this.lifecycleBeganNanos;
	}
}

/** Frozen cleanup evidence shared by the caller and terminal reporter. */
@Immutable
final class InternalShutdownCleanupOutcome {
	@NonNull
	private final InternalShutdownCleanupDisposition disposition;
	@NonNull
	private final Optional<Duration> configuredTimeout;
	@NonNull
	private final Optional<Throwable> failure;
	private final boolean workerMayRemain;
	private final long publicationNanos;

	InternalShutdownCleanupOutcome(
			@NonNull InternalShutdownCleanupDisposition disposition,
			@NonNull Optional<Duration> configuredTimeout,
			@NonNull Optional<? extends Throwable> failure,
			boolean workerMayRemain, long publicationNanos) {
		this.disposition = requireNonNull(disposition);
		this.configuredTimeout = requireNonNull(configuredTimeout);
		this.failure = requireNonNull(failure).map(throwable -> throwable);
		this.workerMayRemain = workerMayRemain;
		this.publicationNanos = publicationNanos;
		if ((disposition == InternalShutdownCleanupDisposition.FAILED
				|| disposition == InternalShutdownCleanupDisposition.TIMED_OUT)
				!= this.failure.isPresent())
			throw new IllegalArgumentException(
					"Cleanup failure evidence does not match its disposition");
	}

	@NonNull
	InternalShutdownCleanupDisposition disposition() {
		return this.disposition;
	}

	@NonNull
	Optional<Duration> configuredTimeout() {
		return this.configuredTimeout;
	}

	@NonNull
	Optional<Throwable> failure() {
		return this.failure;
	}

	boolean workerMayRemain() {
		return this.workerMayRemain;
	}

	long publicationNanos() {
		return this.publicationNanos;
	}

	boolean failed() {
		return this.disposition == InternalShutdownCleanupDisposition.FAILED
				|| this.disposition == InternalShutdownCleanupDisposition.TIMED_OUT;
	}
}

/** Immutable input to the sole package-private reporter seam. */
@Immutable
final class SokletApplicationTerminalSnapshot {
	@NonNull
	private final InternalLifecycleCoreSnapshot coreSnapshot;
	@NonNull
	private final SokletApplicationPrimaryOutcome primaryOutcome;
	@NonNull
	private final Optional<Throwable> primaryFailure;
	@NonNull
	private final InternalShutdownCleanupOutcome cleanupOutcome;
	@NonNull
	private final SokletApplicationCoreDiagnostics coreDiagnostics;
	private final long runnerBeganNanos;
	private final long reporterDeadlineNanos;

	SokletApplicationTerminalSnapshot(
			@NonNull InternalLifecycleCoreSnapshot coreSnapshot,
			@NonNull SokletApplicationPrimaryOutcome primaryOutcome,
			@NonNull Optional<? extends Throwable> primaryFailure,
			@NonNull InternalShutdownCleanupOutcome cleanupOutcome,
			@NonNull SokletApplicationCoreDiagnostics coreDiagnostics,
			long runnerBeganNanos, long reporterDeadlineNanos) {
		this.coreSnapshot = requireNonNull(coreSnapshot);
		this.primaryOutcome = requireNonNull(primaryOutcome);
		this.primaryFailure = requireNonNull(primaryFailure)
				.map(throwable -> throwable);
		this.cleanupOutcome = requireNonNull(cleanupOutcome);
		this.coreDiagnostics = requireNonNull(coreDiagnostics);
		this.runnerBeganNanos = runnerBeganNanos;
		this.reporterDeadlineNanos = reporterDeadlineNanos;
	}

	@NonNull
	InternalLifecycleCoreSnapshot coreSnapshot() {
		return this.coreSnapshot;
	}

	@NonNull
	SokletApplicationPrimaryOutcome primaryOutcome() {
		return this.primaryOutcome;
	}

	@NonNull
	Optional<Throwable> primaryFailure() {
		return this.primaryFailure;
	}

	@NonNull
	InternalShutdownCleanupOutcome cleanupOutcome() {
		return this.cleanupOutcome;
	}

	@NonNull
	SokletApplicationCoreDiagnostics coreDiagnostics() {
		return this.coreDiagnostics;
	}

	long runnerBeganNanos() {
		return this.runnerBeganNanos;
	}

	long reporterDeadlineNanos() {
		return this.reporterDeadlineNanos;
	}
}

/** One cleanup/reporter attempt shared by the ordinary runner and JVM hook. */
@ThreadSafe
final class SokletApplicationFinalization {
	private static final Duration REPORTER_TIMEOUT = Duration.ofMillis(250);

	@NonNull
	private final NanoClock clock;
	@NonNull
	private final DeadlineWaiter waiter;
	@NonNull
	private final LifecycleWorkers workers;
	@Nullable
	private final ShutdownCleanup shutdownCleanup;
	@NonNull
	private final LifecycleTerminalReporter reporter;
	private final long runnerBeganNanos;
	@NonNull
	private final AtomicReference<CorePlan> corePlan;
	@NonNull
	private final AtomicReference<InternalShutdownCleanupOutcome> cleanupOutcome;
	@NonNull
	private final AtomicBoolean cleanupLaunchClaimed;
	@NonNull
	private final AtomicReference<Thread> cleanupThread;
	@NonNull
	private final AtomicBoolean cleanupInterruptRequested;
	@NonNull
	private final AtomicBoolean cleanupInterruptDelivered;
	@NonNull
	private final AtomicBoolean cleanupWorkerFinished;
	@NonNull
	private final AtomicReference<ReporterPlan> reporterPlan;
	@NonNull
	private final AtomicBoolean reporterLaunchClaimed;
	@NonNull
	private final AtomicBoolean reporterFinished;
	@NonNull
	private final AtomicBoolean completionPublished;
	@NonNull
	private final AtomicReference<Supplier<SokletApplicationCoreDiagnostics>>
			diagnosticsSupplier;
	@NonNull
	private final AtomicReference<Function<InternalShutdownResult,
			Optional<RuntimeException>>> terminalFailureClassifier;
	@NonNull
	private final AtomicReference<PrimaryEvidence> primaryEvidence;

	SokletApplicationFinalization(
			@Nullable ShutdownCleanup shutdownCleanup,
			@NonNull LifecycleRuntimeServices services,
			@NonNull LifecycleTerminalReporter reporter) {
		LifecycleRuntimeServices exactServices = requireNonNull(services);
		this.clock = exactServices.clock();
		this.waiter = exactServices.waiter();
		this.workers = exactServices.workers();
		this.shutdownCleanup = shutdownCleanup;
		this.reporter = requireNonNull(reporter);
		this.runnerBeganNanos = this.clock.nanoTime();
		this.corePlan = new AtomicReference<>();
		this.cleanupOutcome = new AtomicReference<>();
		this.cleanupLaunchClaimed = new AtomicBoolean();
		this.cleanupThread = new AtomicReference<>();
		this.cleanupInterruptRequested = new AtomicBoolean();
		this.cleanupInterruptDelivered = new AtomicBoolean();
		this.cleanupWorkerFinished = new AtomicBoolean();
		this.reporterPlan = new AtomicReference<>();
		this.reporterLaunchClaimed = new AtomicBoolean();
		this.reporterFinished = new AtomicBoolean();
		this.completionPublished = new AtomicBoolean();
		this.diagnosticsSupplier = new AtomicReference<>();
		this.terminalFailureClassifier = new AtomicReference<>();
		this.primaryEvidence = new AtomicReference<>();
	}

	void diagnosticsSupplier(
			@NonNull Supplier<SokletApplicationCoreDiagnostics> supplier) {
		if (!this.diagnosticsSupplier.compareAndSet(null,
				requireNonNull(supplier)))
			throw new IllegalStateException(
					"Core diagnostics supplier was already installed");
	}

	void terminalFailureClassifier(@NonNull Function<InternalShutdownResult,
			Optional<RuntimeException>> classifier) {
		if (!this.terminalFailureClassifier.compareAndSet(null,
				requireNonNull(classifier)))
			throw new IllegalStateException(
					"Terminal failure classifier was already installed");
	}

	void notePrimary(@NonNull SokletApplicationPrimaryOutcome outcome,
			@NonNull Throwable failure) {
		if (requireNonNull(outcome)
				!= SokletApplicationPrimaryOutcome.PROCESS_OWNERSHIP_FAILURE)
			throw new IllegalArgumentException(
					"Only pre-publication process ownership failure is explicit");
		this.primaryEvidence.compareAndSet(null,
				new PrimaryEvidence(outcome, requireNonNull(failure)));
	}

	void publishCoreSnapshot(@NonNull InternalLifecycleCoreSnapshot snapshot) {
		InternalLifecycleCoreSnapshot exactSnapshot = requireNonNull(snapshot);
		CorePlan plan = createCorePlan(exactSnapshot);
		if (!this.corePlan.compareAndSet(null, plan)) {
			InternalLifecycleCoreSnapshot installed = requireNonNull(
					this.corePlan.get()).snapshot();
			if (installed.result() != exactSnapshot.result()
					|| installed.publicationNanos()
					!= exactSnapshot.publicationNanos())
				throw new IllegalStateException(
						"A different core snapshot was already published");
			return;
		}
		if (plan.immediateOutcome() != null)
			freezeCleanupOutcome(withPublicationNanos(
					plan.immediateOutcome(), this.clock.nanoTime()));
		this.waiter.signal();
	}

	@NonNull
	AwaitResult awaitCompletion() {
		if (this.corePlan.get() == null)
			throw new IllegalStateException(
					"Core result must be published before finalization is joined");
		boolean interrupted = false;
		while (!this.completionPublished.get()) {
			try {
				advance();
			} catch (InterruptedException exception) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
		return new AwaitResult(requireNonNull(this.cleanupOutcome.get()),
				interrupted);
	}

	@NonNull
	Optional<InternalShutdownCleanupOutcome> cleanupOutcome() {
		return Optional.ofNullable(this.cleanupOutcome.get());
	}

	boolean isComplete() {
		return this.completionPublished.get();
	}

	private void advance() throws InterruptedException {
		CorePlan plan = requireNonNull(this.corePlan.get());
		InternalShutdownCleanupOutcome frozenCleanup = this.cleanupOutcome.get();
		if (frozenCleanup == null) {
			CleanupPlan eligible = plan.cleanupPlan();
			if (eligible == null) {
				freezeCleanupOutcome(withPublicationNanos(
						requireNonNull(plan.immediateOutcome()),
						this.clock.nanoTime()));
				return;
			}
			advanceCleanup(eligible);
			return;
		}

		ReporterPlan exactReporterPlan = ensureReporterPlan(frozenCleanup);
		if (this.reporterFinished.get()) {
			publishCompletion();
			return;
		}
		advanceReporter(exactReporterPlan);
	}

	private void advanceCleanup(@NonNull CleanupPlan plan)
			throws InterruptedException {
		CleanupPlan exactPlan = requireNonNull(plan);
		if (LifecycleDeadlines.remainingNanos(exactPlan.deadlineNanos(),
				this.clock.nanoTime()) == 0L) {
			publishCleanupTimeout(exactPlan);
			return;
		}
		if (this.cleanupLaunchClaimed.compareAndSet(false, true)) {
			if (this.cleanupOutcome.get() == null) {
				try {
					this.workers.start(LifecycleWorkers.Role.SHUTDOWN_CLEANUP,
							"soklet-application-cleanup", exactPlan.task());
				} catch (Throwable launchFailure) {
					freezeCleanupOutcome(new InternalShutdownCleanupOutcome(
							InternalShutdownCleanupDisposition.FAILED,
							Optional.of(exactPlan.timeout()),
							Optional.of(launchFailure), false,
							this.clock.nanoTime()));
				}
			}
		}
		if (this.cleanupOutcome.get() != null)
			return;
		DeadlineWaiter.Outcome waitOutcome = this.waiter.await(
				exactPlan.deadlineNanos(),
				() -> this.cleanupOutcome.get() != null);
		if (waitOutcome == DeadlineWaiter.Outcome.DEADLINE_REACHED)
			publishCleanupTimeout(exactPlan);
	}

	private void runCleanup(@NonNull InternalLifecycleCoreSnapshot snapshot,
			ShutdownCleanup.@NonNull Action action, @NonNull Duration timeout,
			long deadlineNanos) {
		Thread worker = Thread.currentThread();
		this.cleanupThread.set(worker);
		deliverCleanupInterruptIfRequested();
		Throwable actionFailure = null;
		try {
			requireNonNull(action).performCleanup(
					requireNonNull(snapshot).publicResult());
		} catch (Throwable failure) {
			actionFailure = failure;
		} finally {
			long completionNanos = this.clock.nanoTime();
			if (completionNanos > deadlineNanos) {
				this.cleanupWorkerFinished.set(true);
				publishCleanupTimeout(timeout);
			} else {
				freezeCleanupOutcome(new InternalShutdownCleanupOutcome(
						actionFailure == null
								? InternalShutdownCleanupDisposition.SUCCEEDED
								: InternalShutdownCleanupDisposition.FAILED,
						Optional.of(timeout), Optional.ofNullable(actionFailure),
						false, completionNanos));
			}
			this.cleanupWorkerFinished.set(true);
			this.waiter.signal();
		}
	}

	private void publishCleanupTimeout(@NonNull CleanupPlan plan) {
		publishCleanupTimeout(requireNonNull(plan).timeout());
	}

	private void publishCleanupTimeout(@NonNull Duration timeout) {
		TimeoutException timeoutFailure = new TimeoutException(
				"Standalone Soklet cleanup timed out; its daemon action may remain live");
		boolean workerMayRemain = this.cleanupLaunchClaimed.get()
				&& !this.cleanupWorkerFinished.get();
		InternalShutdownCleanupOutcome timedOutOutcome =
				new InternalShutdownCleanupOutcome(
						InternalShutdownCleanupDisposition.TIMED_OUT,
						Optional.of(requireNonNull(timeout)),
						Optional.of(timeoutFailure), workerMayRemain,
						this.clock.nanoTime());
		if (this.cleanupOutcome.compareAndSet(null, timedOutOutcome)) {
			requestCleanupInterruptOnce();
			ensureReporterPlan(timedOutOutcome);
			this.waiter.signal();
		}
	}

	private void requestCleanupInterruptOnce() {
		this.cleanupInterruptRequested.compareAndSet(false, true);
		deliverCleanupInterruptIfRequested();
	}

	private void deliverCleanupInterruptIfRequested() {
		if (!this.cleanupInterruptRequested.get())
			return;
		Thread worker = this.cleanupThread.get();
		if (worker != null
				&& this.cleanupInterruptDelivered.compareAndSet(false, true)) {
			try {
				worker.interrupt();
			} catch (Throwable ignored) {
				// Interrupt delivery cannot revoke an already-frozen timeout or
				// prevent terminal reporting and process-resource release.
			}
		}
	}

	private void freezeCleanupOutcome(
			@NonNull InternalShutdownCleanupOutcome candidate) {
		InternalShutdownCleanupOutcome exactCandidate = requireNonNull(candidate);
		if (this.cleanupOutcome.compareAndSet(null, exactCandidate)) {
			ensureReporterPlan(exactCandidate);
			this.waiter.signal();
		}
	}

	@NonNull
	private static InternalShutdownCleanupOutcome withPublicationNanos(
			@NonNull InternalShutdownCleanupOutcome outcome,
			long publicationNanos) {
		InternalShutdownCleanupOutcome exactOutcome = requireNonNull(outcome);
		return new InternalShutdownCleanupOutcome(exactOutcome.disposition(),
				exactOutcome.configuredTimeout(), exactOutcome.failure(),
				exactOutcome.workerMayRemain(), publicationNanos);
	}

	@NonNull
	private ReporterPlan ensureReporterPlan(
			@NonNull InternalShutdownCleanupOutcome outcome) {
		ReporterPlan installed = this.reporterPlan.get();
		if (installed != null)
			return installed;
		long deadline = LifecycleDeadlines.after(
				requireNonNull(outcome).publicationNanos(), REPORTER_TIMEOUT);
		ReporterPlan candidate = new ReporterPlan(deadline,
				() -> runReporter(outcome, deadline));
		this.reporterPlan.compareAndSet(null, candidate);
		return requireNonNull(this.reporterPlan.get());
	}

	private void advanceReporter(@NonNull ReporterPlan plan)
			throws InterruptedException {
		ReporterPlan exactPlan = requireNonNull(plan);
		if (LifecycleDeadlines.remainingNanos(exactPlan.deadlineNanos(),
				this.clock.nanoTime()) == 0L) {
			finishReporter();
			return;
		}
		if (this.reporterLaunchClaimed.compareAndSet(false, true)) {
			if (!this.reporterFinished.get()) {
				try {
					this.workers.start(LifecycleWorkers.Role.TERMINAL_REPORTER,
							"soklet-application-terminal-reporter",
							exactPlan.task());
				} catch (Throwable ignored) {
					finishReporter();
				}
			}
		}
		if (this.reporterFinished.get()) {
			publishCompletion();
			return;
		}
		DeadlineWaiter.Outcome waitOutcome = this.waiter.await(
				exactPlan.deadlineNanos(),
				() -> this.reporterFinished.get());
		if (waitOutcome == DeadlineWaiter.Outcome.DEADLINE_REACHED)
			finishReporter();
	}

	private void runReporter(@NonNull InternalShutdownCleanupOutcome outcome,
			long deadlineNanos) {
		try {
			SokletApplicationTerminalSnapshot snapshot = terminalSnapshot(
					requireNonNull(outcome), deadlineNanos);
			this.reporter.report(snapshot);
		} catch (Throwable ignored) {
			// Diagnostic preparation and reporting are both contained inside this
			// dedicated deadline-bounded worker.
		} finally {
			finishReporter();
		}
	}

	private void finishReporter() {
		if (this.reporterFinished.compareAndSet(false, true))
			this.waiter.signal();
		publishCompletion();
	}

	private void publishCompletion() {
		if (this.reporterFinished.get()
				&& this.completionPublished.compareAndSet(false, true))
			this.waiter.signal();
	}

	@NonNull
	private CorePlan createCorePlan(
			@NonNull InternalLifecycleCoreSnapshot snapshot) {
		InternalLifecycleCoreSnapshot exactSnapshot = requireNonNull(snapshot);
		ShutdownCleanup shutdownCleanup = this.shutdownCleanup;
		if (shutdownCleanup == null) {
			return new CorePlan(exactSnapshot, null,
					new InternalShutdownCleanupOutcome(
							InternalShutdownCleanupDisposition.NOT_CONFIGURED,
							Optional.empty(), Optional.empty(), false,
							exactSnapshot.publicationNanos()));
		}
		Duration timeout = shutdownCleanup.getTimeout();
		if (!exactSnapshot.result().isComplete()) {
			return new CorePlan(exactSnapshot, null,
					new InternalShutdownCleanupOutcome(
							InternalShutdownCleanupDisposition
									.SKIPPED_INCOMPLETE_SHUTDOWN,
							Optional.of(timeout), Optional.empty(), false,
							exactSnapshot.publicationNanos()));
		}
		long deadline = LifecycleDeadlines.after(
				exactSnapshot.publicationNanos(), timeout);
		CleanupPlan plan = new CleanupPlan(timeout, deadline,
				() -> runCleanup(exactSnapshot, shutdownCleanup.action(),
						timeout, deadline));
		return new CorePlan(exactSnapshot, plan, null);
	}

	@NonNull
	private SokletApplicationTerminalSnapshot terminalSnapshot(
			@NonNull InternalShutdownCleanupOutcome cleanupOutcome,
			long reporterDeadlineNanos) {
		CorePlan plan = requireNonNull(this.corePlan.get());
		PrimaryEvidence explicitPrimary = this.primaryEvidence.get();
		RuntimeException classifiedFailure = explicitPrimary == null
				? classifyTerminalFailure(plan.snapshot().result()).orElse(null)
				: null;
		SokletApplicationPrimaryOutcome primaryOutcome;
		Optional<Throwable> primaryFailure;
		if (explicitPrimary != null) {
			primaryOutcome = explicitPrimary.outcome();
			primaryFailure = Optional.of(explicitPrimary.failure());
		} else if (classifiedFailure != null) {
			primaryOutcome = primaryOutcome(classifiedFailure);
			primaryFailure = Optional.of(reportablePrimaryFailure(
					classifiedFailure));
		} else {
			primaryOutcome = derivePrimaryOutcome(plan.snapshot().result());
			primaryFailure = firstLifecycleFailure(plan.snapshot().result());
		}
		SokletApplicationCoreDiagnostics diagnostics;
		try {
			Supplier<SokletApplicationCoreDiagnostics> supplier = requireNonNull(
					this.diagnosticsSupplier.get(),
					"Core diagnostics supplier is not installed");
			diagnostics = requireNonNull(supplier.get(),
					"Core diagnostics supplier returned null");
		} catch (Throwable ignored) {
			diagnostics = unavailableDiagnostics();
		}
		return new SokletApplicationTerminalSnapshot(plan.snapshot(),
				primaryOutcome, primaryFailure, cleanupOutcome, diagnostics,
				this.runnerBeganNanos, reporterDeadlineNanos);
	}

	@NonNull
	private SokletApplicationCoreDiagnostics unavailableDiagnostics() {
		return new SokletApplicationCoreDiagnostics(
				new LifecycleTransitionSnapshot(0, 0, false, true, false,
						0, Optional.empty()),
				Map.of(), InternalLifecyclePolicy.defaults(),
				this.runnerBeganNanos);
	}

	@NonNull
	private Optional<RuntimeException> classifyTerminalFailure(
			@NonNull InternalShutdownResult result) {
		Function<InternalShutdownResult, Optional<RuntimeException>> classifier =
				this.terminalFailureClassifier.get();
		if (classifier == null)
			return Optional.empty();
		try {
			return requireNonNull(classifier.apply(requireNonNull(result)),
					"Terminal failure classifier returned null");
		} catch (Throwable ignored) {
			return Optional.empty();
		}
	}

	@NonNull
	private static SokletApplicationPrimaryOutcome primaryOutcome(
			@NonNull RuntimeException failure) {
		if (failure instanceof SokletTerminatedUnexpectedlyException)
			return SokletApplicationPrimaryOutcome.UNEXPECTED_TERMINATION;
		if (failure instanceof ShutdownIncompleteException)
			return SokletApplicationPrimaryOutcome.INCOMPLETE_SHUTDOWN;
		return SokletApplicationPrimaryOutcome.STARTUP_FAILURE;
	}

	@NonNull
	private static Throwable reportablePrimaryFailure(
			@NonNull RuntimeException failure) {
		RuntimeException exactFailure = requireNonNull(failure);
		if (exactFailure instanceof SokletStartupException
				&& exactFailure.getCause() != null)
			return exactFailure.getCause();
		return exactFailure;
	}

	@NonNull
	private static SokletApplicationPrimaryOutcome derivePrimaryOutcome(
			@NonNull InternalShutdownResult result) {
		InternalShutdownResult exactResult = requireNonNull(result);
		if (exactResult.startupDisposition()
				== InternalStartupDisposition.FAILED
				|| exactResult.startupDisposition()
				== InternalStartupDisposition.TIMED_OUT)
			return SokletApplicationPrimaryOutcome.STARTUP_FAILURE;
		if (exactResult.participantResults().stream().anyMatch(participant ->
				participant.disposition()
						== InternalLifecycleComponentShutdownDisposition
								.UNEXPECTED_TERMINATION))
			return SokletApplicationPrimaryOutcome.UNEXPECTED_TERMINATION;
		if (!exactResult.isComplete())
			return SokletApplicationPrimaryOutcome.INCOMPLETE_SHUTDOWN;
		return SokletApplicationPrimaryOutcome.EXPECTED;
	}

	@NonNull
	private static Optional<Throwable> firstLifecycleFailure(
			@NonNull InternalShutdownResult result) {
		return requireNonNull(result).participantResults().stream()
				.flatMap(participant -> participant.failures().stream())
				.findFirst();
	}

	@NonNull
	static SokletApplicationCleanupException cleanupException(
			@NonNull InternalShutdownResult result,
			@NonNull InternalShutdownCleanupOutcome outcome) {
		return cleanupException(ShutdownResult.fromInternal(result), outcome);
	}

	@NonNull
	static SokletApplicationCleanupException cleanupException(
			@NonNull ShutdownResult result,
			@NonNull InternalShutdownCleanupOutcome outcome) {
		InternalShutdownCleanupOutcome exactOutcome = requireNonNull(outcome);
		ShutdownCleanupFailure failure = switch (exactOutcome.disposition()) {
			case FAILED -> ShutdownCleanupFailure.FAILED;
			case TIMED_OUT -> ShutdownCleanupFailure.TIMED_OUT;
			default -> throw new IllegalArgumentException(
					"Cleanup outcome is not a failure");
		};
		return new SokletApplicationCleanupException(failure,
				exactOutcome.configuredTimeout().orElseThrow(),
				requireNonNull(result), exactOutcome.failure().orElseThrow());
	}

	@Immutable
	record AwaitResult(@NonNull InternalShutdownCleanupOutcome cleanupOutcome,
			boolean interrupted) {
		AwaitResult {
			requireNonNull(cleanupOutcome);
		}
	}

	@Immutable
	private record CorePlan(@NonNull InternalLifecycleCoreSnapshot snapshot,
			@Nullable CleanupPlan cleanupPlan,
			@Nullable InternalShutdownCleanupOutcome immediateOutcome) {
		private CorePlan {
			requireNonNull(snapshot);
			if ((cleanupPlan == null) == (immediateOutcome == null))
				throw new IllegalArgumentException(
						"Core plan requires exactly one cleanup path");
		}
	}

	@Immutable
	private record CleanupPlan(@NonNull Duration timeout, long deadlineNanos,
			@NonNull Runnable task) {
		private CleanupPlan {
			requireNonNull(timeout);
			requireNonNull(task);
		}
	}

	@Immutable
	private record ReporterPlan(long deadlineNanos, @NonNull Runnable task) {
		private ReporterPlan {
			requireNonNull(task);
		}
	}

	@Immutable
	private record PrimaryEvidence(
			@NonNull SokletApplicationPrimaryOutcome outcome,
			@NonNull Throwable failure) {
		private PrimaryEvidence {
			requireNonNull(outcome);
			requireNonNull(failure);
		}
	}
}
