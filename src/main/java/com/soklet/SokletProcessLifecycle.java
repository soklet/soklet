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
import java.util.Optional;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static com.soklet.internal.ObjectIdentity.sameInstance;
import static java.util.Objects.requireNonNull;

/**
 * Configured, one-shot standalone-process adapter for one Soklet lifecycle.
 * <p>
 * Each {@code run(...)} method blocks through finalization. If its calling
 * thread is interrupted, the runner requests shutdown, joins the lifecycle
 * uninterruptibly, and restores the calling thread's interrupt status before
 * returning or throwing. The runner owns its JVM shutdown hook for the duration
 * of the run and releases it before returning, unless JVM shutdown is underway.
 * Console input and other application events remain application-owned.
 * Application cleanup begins only after a complete core shutdown. A cleanup
 * timeout bounds the runner's wait and reporting; it does not imply that
 * arbitrary application cleanup code was forcibly stopped.
 * <p>
 * Finalization attempts a {@code soklet-terminal-report} directly on
 * standard error, captured when the run's runtime is created, when the
 * runner's primary outcome indicates failure, startup failed or timed out, shutdown is
 * forced or incomplete, a component terminated unexpectedly, or application
 * cleanup failed or timed out. A complete forced shutdown therefore still
 * triggers a report; ordinary expected graceful shutdown does not.
 * This diagnostic bypasses {@link LifecycleObserver} and {@link LogEvent}.
 * It is capped at 16 KiB of valid UTF-8 and reports failure class names
 * without exception messages, stack traces or cause/suppressed traversal.
 * Reporting is best effort with one shared 250-millisecond finalization
 * allowance; reporter failure or timeout does not replace the lifecycle
 * result. A blocked output worker may remain after that allowance. No public
 * reporter switch is provided; configure process stderr capture as needed.
 * <p>
 * Shutdown requested during startup may return a terminal result without
 * reaching readiness; it does not by itself cause a startup exception.
 * Inspect the returned result to determine the lifecycle outcome.
 * <p>
 * JVM shutdown hooks run concurrently with no guaranteed ordering. On a
 * signal-driven JVM shutdown, the JVM may terminate before {@code run(...)},
 * a caller's {@code finally} block, or post-run code can finish. Put required
 * application cleanup in bounded {@link ShutdownCleanup} and coordinate any
 * independently registered logging/resource shutdown hooks. The runner does
 * not choose a process exit status.
 * <p>
 * Soklet-created auxiliary workers are daemon threads. A residual handler or
 * callback on those workers therefore does not by itself keep the JVM alive
 * after this run ends. Built-in listener threads are non-daemon; an inline
 * connection observer or metric callback that never returns can retain that
 * listener and process liveness. Keep those callbacks prompt. Daemon status
 * does not establish termination proof or make an
 * incomplete result complete. Running built-in listeners retain process
 * liveness. Custom transports and supplied executors own their thread and
 * process-liveness policies.
 */
@ThreadSafe
final class SokletProcessLifecycle {
	@NonNull
	static final SystemLifecycleProcessAccess SYSTEM_PROCESS =
			new SystemLifecycleProcessAccess();
	@NonNull
	private final SokletConfig config;
	@NonNull
	private final AtomicBoolean runClaimed;
	@Nullable
	private SokletRunCompletion managedRun;

	private SokletProcessLifecycle(@NonNull SokletConfig sokletConfig) {
		this.config = requireNonNull(sokletConfig);
		this.runClaimed = new AtomicBoolean();
	}

	/** Launches the lifecycle owner; neither readiness nor finalization needs a public waiter. */
	@NonNull
	static Soklet launch(@NonNull SokletConfig config,
			@Nullable ShutdownCleanup cleanup) {
		return launch(config, cleanup,
				task -> new Thread(task, "soklet-process-lifecycle"),
				SokletRunCompletion::launchDaemonHandoff);
	}

	/** Injectable thread-launch seams, with reservation and claims in production order. */
	@NonNull
	static Soklet launch(@NonNull SokletConfig config,
			@Nullable ShutdownCleanup cleanup,
			@NonNull ThreadFactory ownerFactory,
			LifecycleWorkers.@NonNull Launcher handoffLauncher) {
		SokletProcessLifecycle process = new SokletProcessLifecycle(requireNonNull(config));
		requireNonNull(ownerFactory);
		requireNonNull(handoffLauncher);
		boolean interrupted = Thread.currentThread().isInterrupted();
		try {
			// Failure to reserve the daemon is precommit: no transports or hooks exist.
			SokletRunCompletion completion = new SokletRunCompletion(handoffLauncher);
			process.managedRun = completion;
			if (interrupted)
				completion.cancelStartup();
			Thread coordinator;
			try {
				coordinator = requireNonNull(ownerFactory.newThread(() -> {
					if (interrupted)
						Thread.currentThread().interrupt();
					try {
						ShutdownResult result = process.runPublic(cleanup);
						if (result.getStartupDisposition() != StartupDisposition.READY)
							throw new SokletStartupException(result);
						completion.finish(result, null);
					} catch (Throwable failure) {
						completion.finish(null, failure);
					}
				}));
				// Keep finalization alive even after the final transport listener exits.
				coordinator.setDaemon(false);
				coordinator.start();
			} catch (RuntimeException | Error launchFailure) {
				// Construction, daemon-policy, and start failures must all retire
				// the reserved handoff, even though transport claiming never began.
				completion.finish(null, launchFailure);
				throw launchFailure;
			}
			// awaitReady may throw the owner's already-published failure. It must
			// stay outside the launch catch to avoid publishing that outcome twice.
			return completion.awaitReady();
		} finally {
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	@NonNull
	public static ShutdownResult run(@NonNull SokletConfig sokletConfig) {
		return fromConfig(sokletConfig).run();
	}

	@NonNull
	public static SokletProcessLifecycle fromConfig(@NonNull SokletConfig sokletConfig) {
		return new SokletProcessLifecycle(sokletConfig);
	}

	@NonNull
	public ShutdownResult run() {
		return runPublic(null);
	}

	@NonNull
	public ShutdownResult run(@NonNull ShutdownCleanup shutdownCleanup) {
		return runPublic(requireNonNull(shutdownCleanup));
	}

	@NonNull
	private ShutdownResult runPublic(@Nullable ShutdownCleanup shutdownCleanup) {
		claimRun();
		return runCore(SokletApplicationEnvironment.system(), shutdownCleanup).publicResult();
	}

	@NonNull
	InternalShutdownResult run(@NonNull SokletApplicationEnvironment environment) {
		return runInternal(environment, null);
	}

	@NonNull
	InternalShutdownResult run(@NonNull SokletApplicationEnvironment environment,
			@NonNull ShutdownCleanup shutdownCleanup) {
		return runInternal(environment, requireNonNull(shutdownCleanup));
	}

	@NonNull
	private InternalShutdownResult runInternal(@NonNull SokletApplicationEnvironment environment,
			@Nullable ShutdownCleanup shutdownCleanup) {
		claimRun();
		return runCore(requireNonNull(environment), shutdownCleanup).result();
	}

	private void claimRun() {
		if (!this.runClaimed.compareAndSet(false, true))
			throw new IllegalStateException(
					"This SokletProcessLifecycle lifecycle was already claimed");
	}

	@NonNull
	private InternalLifecycleCoreSnapshot runCore(
			@NonNull SokletApplicationEnvironment environment,
			@Nullable ShutdownCleanup shutdownCleanup) {
		// Every validation in this block precedes the transport-identity commit
		// performed by the runtime factory.
		SokletApplicationEnvironment exactEnvironment =
				requireNonNull(environment);
		LifecycleRuntimeServices services = exactEnvironment.services();
		SokletRunCompletion managed = this.managedRun;
		ShutdownCleanup guardedCleanup = shutdownCleanup == null || managed == null
				? shutdownCleanup : managed.guardCleanup(shutdownCleanup);
		SokletApplicationFinalization finalization =
				new SokletApplicationFinalization(guardedCleanup, services,
						exactEnvironment.reporter());

		// Successful factory return is the one ownership commit.  A failure here
		// remains precommit and therefore creates no hook, cleanup, or report.
		SokletApplicationRuntime runtime = exactEnvironment.runtimeFactory()
				.create(this.config, services, finalization::publishCoreSnapshot);
		if (managed != null)
			managed.bind(((DirectSokletApplicationRuntime) runtime).soklet());
		finalization.diagnosticsSupplier(runtime::diagnostics);
		finalization.terminalFailureClassifier(runtime::terminalFailure);
		Attempt attempt = new Attempt(runtime, finalization);
		Thread hook = null;
		boolean hookRegistered = false;
		Throwable processOwnershipFailure = null;
		Throwable startFailure = null;
		boolean skipStart = false;
		boolean interrupted = false;

		try {
			hook = exactEnvironment.hookFactory().create(
					"soklet-application-shutdown-hook", attempt::runHook);
		} catch (Throwable failure) {
			processOwnershipFailure = failure;
			finalization.notePrimary(
					SokletApplicationPrimaryOutcome.PROCESS_OWNERSHIP_FAILURE,
					failure);
			attempt.requestShutdown(TriggerSource.PROCESS_OWNERSHIP_FAILURE);
			skipStart = true;
		}
		if (!skipStart) {
			try {
				exactEnvironment.processAccess().addShutdownHook(
						requireNonNull(hook));
				hookRegistered = true;
			} catch (IllegalStateException shutdownInProgress) {
				attempt.requestShutdown(
						TriggerSource.HOOK_REGISTRATION_SHUTDOWN);
				skipStart = true;
			} catch (Throwable failure) {
				processOwnershipFailure = failure;
				finalization.notePrimary(
						SokletApplicationPrimaryOutcome.PROCESS_OWNERSHIP_FAILURE,
						failure);
				attempt.requestShutdown(
						TriggerSource.PROCESS_OWNERSHIP_FAILURE);
				skipStart = true;
			}
		}

		if (!skipStart) {
			try {
				runtime.start();
				if (managed != null)
					managed.publishReady();
			} catch (Throwable failure) {
				startFailure = failure;
			}
		}

		CoreJoin coreJoin = awaitCore(runtime, attempt, true);
		interrupted |= coreJoin.interrupted();
		InternalLifecycleCoreSnapshot coreSnapshot = coreJoin.snapshot();
		// Fallback publication is idempotent and covers a failed internal
		// callback without moving the absolute deadline.
		finalization.publishCoreSnapshot(coreSnapshot);

		RuntimeException primary = classifyPrimary(runtime, coreSnapshot,
				processOwnershipFailure, startFailure);
		if (managed != null && startFailure instanceof SokletStartupException startupFailure)
			primary = startupFailure;
		if (managed != null && primary == null
				&& coreSnapshot.publicResult().getStartupDisposition() != StartupDisposition.READY)
			primary = new SokletStartupException(coreSnapshot.publicResult());

		SokletApplicationFinalization.AwaitResult finalizationResult =
				finalization.awaitCompletion();
		interrupted |= finalizationResult.interrupted();

		Throwable processReleaseFailure = null;
		if (hookRegistered) {
			try {
				exactEnvironment.processAccess().removeShutdownHook(
						requireNonNull(hook));
			} catch (IllegalStateException ignored) {
				// JVM shutdown won concurrently; the hook has already joined the
				// same bounded finalization completion.
			} catch (Throwable failure) {
				processReleaseFailure = failure;
			}
		}

		if (interrupted)
			Thread.currentThread().interrupt();

		InternalShutdownCleanupOutcome cleanupOutcome =
				finalizationResult.cleanupOutcome();
		SokletShutdownCleanupException cleanupFailure =
				cleanupOutcome.failed()
						? SokletApplicationFinalization.cleanupException(
								coreSnapshot.publicResult(), cleanupOutcome)
						: null;
		if (primary != null) {
			if (cleanupFailure != null)
				addSuppressedOnce(primary, cleanupFailure);
			if (processReleaseFailure != null)
				addSuppressedOnce(primary, processReleaseFailure);
			throw primary;
		}
		if (cleanupFailure != null) {
			if (processReleaseFailure != null)
				addSuppressedOnce(cleanupFailure, processReleaseFailure);
			throw cleanupFailure;
		}
		if (processReleaseFailure != null) {
			if (processReleaseFailure instanceof RuntimeException runtimeFailure)
				throw runtimeFailure;
			if (processReleaseFailure instanceof Error error)
				throw error;
			throw new IllegalStateException(
					"Unable to release standalone Soklet process ownership",
					processReleaseFailure);
		}
		return coreSnapshot;
	}

	@NonNull
	private static CoreJoin awaitCore(@NonNull SokletApplicationRuntime runtime,
			@NonNull Attempt attempt, boolean requestShutdownOnInterrupt) {
		boolean interrupted = false;
		for (;;) {
			try {
				return new CoreJoin(requireNonNull(runtime).awaitCore(),
						interrupted);
			} catch (InterruptedException exception) {
				interrupted = true;
				if (requestShutdownOnInterrupt)
					requireNonNull(attempt).requestShutdown(
							TriggerSource.INTERRUPTION);
			}
		}
	}

	@Nullable
	private static RuntimeException classifyPrimary(
			@NonNull SokletApplicationRuntime runtime,
			@NonNull InternalLifecycleCoreSnapshot snapshot,
			@Nullable Throwable processOwnershipFailure,
			@Nullable Throwable startFailure) {
		InternalLifecycleCoreSnapshot exactSnapshot = requireNonNull(snapshot);
		InternalShutdownResult exactResult = exactSnapshot.result();
		if (processOwnershipFailure != null)
			return new SokletStartupException(exactSnapshot.publicResult(),
					processOwnershipFailure);
		if (startFailure != null) {
			if (startFailure instanceof SokletStartupException startupFailure
					&& (startupFailure.getInternalStartupDisposition()
							== InternalStartupDisposition.NOT_ATTEMPTED
							|| startupFailure.getInternalStartupDisposition()
							== InternalStartupDisposition.CANCELED))
				return requireNonNull(runtime).terminalFailure(exactResult)
						.orElse(null);
			if (startFailure instanceof RuntimeException runtimeFailure)
				return runtimeFailure;
			return new SokletStartupException(exactSnapshot.publicResult(),
					startFailure);
		}
		return requireNonNull(runtime).terminalFailure(exactResult).orElse(null);
	}

	private static void addSuppressedOnce(@NonNull Throwable primary,
			@NonNull Throwable secondary) {
		Throwable exactPrimary = requireNonNull(primary);
		Throwable exactSecondary = requireNonNull(secondary);
		if (sameInstance(exactPrimary, exactSecondary))
			return;
		for (Throwable existing : exactPrimary.getSuppressed())
			if (sameInstance(existing, exactSecondary))
				return;
		exactPrimary.addSuppressed(exactSecondary);
	}

	private enum TriggerSource {
		HOOK,
		INTERRUPTION,
		HOOK_REGISTRATION_SHUTDOWN,
		PROCESS_OWNERSHIP_FAILURE
	}

	@ThreadSafe
	private static final class Attempt {
		@NonNull
		private final SokletApplicationRuntime runtime;
		@NonNull
		private final SokletApplicationFinalization finalization;
		private Attempt(@NonNull SokletApplicationRuntime runtime,
				@NonNull SokletApplicationFinalization finalization) {
			this.runtime = requireNonNull(runtime);
			this.finalization = requireNonNull(finalization);
		}

		void requestShutdown(@NonNull TriggerSource source) {
			requireNonNull(source);
			this.runtime.shutdown();
		}

		void runHook() {
			try {
				requestShutdown(TriggerSource.HOOK);
				CoreJoin core = awaitCore(this.runtime, this, false);
				this.finalization.publishCoreSnapshot(core.snapshot());
				this.finalization.awaitCompletion();
				// JVM shutdown must remain bounded even if the process owner is
				// stuck in application code during process ownership. Normal
				// awaits still include registration release; JVM exit retires any
				// remaining process registrations without joining that owner.
			} catch (Throwable ignored) {
				// A JVM hook never propagates and never controls process exit.
			}
		}
	}

	@Immutable
	private record CoreJoin(@NonNull InternalLifecycleCoreSnapshot snapshot,
			boolean interrupted) {
		private CoreJoin {
			requireNonNull(snapshot);
		}
	}
}

interface SokletApplicationRuntimeFactory {
	@NonNull
	SokletApplicationRuntime create(@NonNull SokletConfig config,
			@NonNull LifecycleRuntimeServices services,
			@NonNull Consumer<InternalLifecycleCoreSnapshot> coreSnapshotPublisher);
}

interface SokletApplicationRuntime {
	void start();

	void shutdown();

	@NonNull
	InternalLifecycleCoreSnapshot awaitCore() throws InterruptedException;

	@NonNull
	Optional<RuntimeException> terminalFailure(
			@NonNull InternalShutdownResult result);

	@NonNull
	SokletApplicationCoreDiagnostics diagnostics();
}

@FunctionalInterface
interface SokletApplicationHookFactory {
	@NonNull
	Thread create(@NonNull String name, @NonNull Runnable task);
}

/** Production adapter over the same private completion used by direct Soklet. */
final class DirectSokletApplicationRuntime implements SokletApplicationRuntime {
	@NonNull
	private final Soklet soklet;

	private DirectSokletApplicationRuntime(@NonNull Soklet soklet) {
		this.soklet = requireNonNull(soklet);
	}

	@NonNull
	Soklet soklet() {
		return this.soklet;
	}

	@NonNull
	static DirectSokletApplicationRuntime create(@NonNull SokletConfig config,
			@NonNull LifecycleRuntimeServices services,
			@NonNull Consumer<InternalLifecycleCoreSnapshot> publisher) {
		return new DirectSokletApplicationRuntime(Soklet.fromConfig(config,
				services, publisher));
	}

	@Override
	public void start() {
		this.soklet.start();
	}

	@Override
	public void shutdown() {
		this.soklet.getDirectLifecycle().shutdown();
	}

	@NonNull
	@Override
	public InternalLifecycleCoreSnapshot awaitCore()
			throws InterruptedException {
		this.soklet.getDirectLifecycle().awaitCompletion();
		return this.soklet.getDirectLifecycle().terminalCoreSnapshot();
	}

	@NonNull
	@Override
	public Optional<RuntimeException> terminalFailure(
			@NonNull InternalShutdownResult result) {
		return this.soklet.getDirectLifecycle().applicationTerminalFailure(
				requireNonNull(result));
	}

	@NonNull
	@Override
	public SokletApplicationCoreDiagnostics diagnostics() {
		return this.soklet.getDirectLifecycle().applicationDiagnostics();
	}
}

/** All process-bound dependencies are injectable without public surface. */
@Immutable
record SokletApplicationEnvironment(
		@NonNull LifecycleRuntimeServices services,
		@NonNull LifecycleProcessAccess processAccess,
		@NonNull LifecycleTerminalReporter reporter,
		@NonNull SokletApplicationRuntimeFactory runtimeFactory,
		@NonNull SokletApplicationHookFactory hookFactory) {
	SokletApplicationEnvironment {
		requireNonNull(services);
		requireNonNull(processAccess);
		requireNonNull(reporter);
		requireNonNull(runtimeFactory);
		requireNonNull(hookFactory);
	}

	@NonNull
	static SokletApplicationEnvironment system() {
		return new SokletApplicationEnvironment(
				LifecycleRuntimeServices.system(), SokletProcessLifecycle.SYSTEM_PROCESS,
				DefaultLifecycleTerminalReporter.system(),
				DirectSokletApplicationRuntime::create,
				(name, task) -> new Thread(task, name));
	}
}

/** Actual JVM process seam used only by the package-private runner. */
final class SystemLifecycleProcessAccess implements LifecycleProcessAccess {
	@Override
	public void addShutdownHook(@NonNull Thread hook) {
		Runtime.getRuntime().addShutdownHook(requireNonNull(hook));
	}

	@Override
	public boolean removeShutdownHook(@NonNull Thread hook) {
		return Runtime.getRuntime().removeShutdownHook(requireNonNull(hook));
	}

}
