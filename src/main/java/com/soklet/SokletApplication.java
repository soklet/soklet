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
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
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
 * returning or throwing. The runner owns its JVM shutdown hook and any
 * additional shutdown-trigger registrations for the duration of the run.
 * The core {@link Soklet} lifecycle neither installs those process resources
 * nor closes standard input. Each registration is released before a run
 * returns or throws, except that a JVM shutdown hook already executing is
 * allowed to complete with the process.
 * The optional stdin listener does not add read-ahead buffers. If its last
 * registration is removed during a blocking read, that daemon listener may
 * consume the one pending byte before retiring; it cannot portably cancel the
 * read without closing process-owned stdin. Reserve stdin for the trigger
 * until that pending read completes.
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
public final class SokletApplication {
	@NonNull
	private static final Set<@NonNull ShutdownTrigger>
			NO_ADDITIONAL_SHUTDOWN_TRIGGERS = Collections.unmodifiableSet(
					EnumSet.noneOf(ShutdownTrigger.class));
	@NonNull
	static final SystemLifecycleProcessAccess SYSTEM_PROCESS =
			new SystemLifecycleProcessAccess();
	@NonNull
	static final SokletApplicationInputManager SYSTEM_INPUT =
			new SokletApplicationInputManager(SYSTEM_PROCESS,
					SokletApplicationInputManager::launchDaemon);
	@NonNull
	private final SokletConfig config;
	@NonNull
	private final AtomicBoolean runClaimed;

	private SokletApplication(@NonNull SokletConfig sokletConfig) {
		this.config = requireNonNull(sokletConfig);
		this.runClaimed = new AtomicBoolean();
	}

	/**
	 * Runs one standalone Soklet lifecycle with the default runner settings.
	 * If the calling thread is interrupted, this method requests shutdown, joins
	 * the lifecycle uninterruptibly, and restores the calling thread's interrupt
	 * status before returning or throwing.
	 *
	 * @param sokletConfig the one-shot Soklet configuration
	 * @return the exact immutable lifecycle result
	 * @throws TransportOwnershipException if a configured transport identity
	 * has already been claimed by another lifecycle
	 * @throws SokletStartupException if startup or process-hook registration fails
	 * @throws SokletUnexpectedTerminationException if a transport terminates
	 * unexpectedly after readiness
	 * @throws SokletShutdownIncompleteException if shutdown cannot be proven complete
	 */
	@NonNull
	public static ShutdownResult run(@NonNull SokletConfig sokletConfig) {
		return fromConfig(sokletConfig).run();
	}

	/**
	 * Runs one standalone Soklet lifecycle with the default settings plus the
	 * supplied runner-scoped shutdown triggers.
	 * If the calling thread is interrupted, this method requests shutdown, joins
	 * the lifecycle uninterruptibly, and restores the calling thread's interrupt
	 * status before returning or throwing.
	 *
	 * @param sokletConfig the one-shot Soklet configuration
	 * @param additionalShutdownTriggers non-null additional shutdown triggers
	 * installed and later released by this runner; each element must be non-null
	 * @return the exact immutable lifecycle result
	 * @throws TransportOwnershipException if a configured transport identity
	 * has already been claimed by another lifecycle
	 * @throws SokletStartupException if startup or process-hook registration fails
	 * @throws SokletUnexpectedTerminationException if a transport terminates
	 * unexpectedly after readiness
	 * @throws SokletShutdownIncompleteException if shutdown cannot be proven complete
	 */
	@NonNull
	public static ShutdownResult run(@NonNull SokletConfig sokletConfig,
			@NonNull ShutdownTrigger @NonNull... additionalShutdownTriggers) {
		return fromConfig(sokletConfig).run(additionalShutdownTriggers);
	}

	/**
	 * Creates a configured, one-shot standalone application. Transport
	 * identities are claimed when {@code run(...)} begins, not by this factory.
	 *
	 * @param sokletConfig the one-shot Soklet configuration
	 * @return a configured standalone application
	 */
	@NonNull
	public static SokletApplication fromConfig(
			@NonNull SokletConfig sokletConfig) {
		return new SokletApplication(sokletConfig);
	}

	/**
	 * Runs this configured application lifecycle with the default runner
	 * settings. This method may be invoked exactly once on each application
	 * instance.
	 * If the calling thread is interrupted, this method requests shutdown, joins
	 * the lifecycle uninterruptibly, and restores the calling thread's interrupt
	 * status before returning or throwing.
	 *
	 * @return the exact immutable lifecycle result
	 * @throws TransportOwnershipException if a configured transport identity
	 * has already been claimed by another lifecycle
	 * @throws SokletStartupException if startup or process-hook registration fails
	 * @throws SokletUnexpectedTerminationException if a transport terminates
	 * unexpectedly after readiness
	 * @throws SokletShutdownIncompleteException if shutdown cannot be proven complete
	 * @throws IllegalStateException if a run was already claimed for this
	 * application instance
	 */
	@NonNull
	public ShutdownResult run() {
		return runPublic(null, NO_ADDITIONAL_SHUTDOWN_TRIGGERS);
	}

	/**
	 * Runs this configured application lifecycle with the supplied
	 * runner-scoped shutdown triggers. All arguments are validated and
	 * snapshotted before this application's one run is claimed.
	 * If the calling thread is interrupted, this method requests shutdown, joins
	 * the lifecycle uninterruptibly, and restores the calling thread's interrupt
	 * status before returning or throwing.
	 *
	 * @param additionalShutdownTriggers non-null additional shutdown triggers
	 * installed and later released by this runner; each element must be non-null
	 * @return the exact immutable lifecycle result
	 * @throws TransportOwnershipException if a configured transport identity
	 * has already been claimed by another lifecycle
	 * @throws SokletStartupException if startup or process-hook registration fails
	 * @throws SokletUnexpectedTerminationException if a transport terminates
	 * unexpectedly after readiness
	 * @throws SokletShutdownIncompleteException if shutdown cannot be proven complete
	 * @throws IllegalStateException if a run was already claimed for this
	 * application instance
	 */
	@NonNull
	public ShutdownResult run(
			@NonNull ShutdownTrigger @NonNull... additionalShutdownTriggers) {
		Set<@NonNull ShutdownTrigger> triggerSnapshot =
				snapshot(additionalShutdownTriggers);
		return runPublic(null, triggerSnapshot);
	}

	/**
	 * Runs this configured application lifecycle with bounded cleanup and any
	 * supplied runner-scoped shutdown triggers. Cleanup is eligible at most once,
	 * only after the core shutdown result is complete. All arguments are
	 * validated and snapshotted before this application's one run is claimed.
	 * If the calling thread is interrupted, this method requests shutdown, joins
	 * the lifecycle uninterruptibly, and restores the calling thread's interrupt
	 * status before returning or throwing. A cleanup timeout does not imply that
	 * arbitrary application cleanup code was forcibly stopped.
	 *
	 * @param shutdownCleanup bounded synchronous cleanup specification
	 * @param additionalShutdownTriggers non-null additional shutdown triggers
	 * installed and later released by this runner; each element must be non-null
	 * @return the exact immutable lifecycle result
	 * @throws TransportOwnershipException if a configured transport identity
	 * has already been claimed by another lifecycle
	 * @throws SokletStartupException if startup or process-hook registration fails
	 * @throws SokletUnexpectedTerminationException if a transport terminates
	 * unexpectedly after readiness
	 * @throws SokletShutdownIncompleteException if shutdown cannot be proven complete
	 * @throws SokletShutdownCleanupException if eligible cleanup fails or
	 * exceeds its configured deadline
	 * @throws IllegalStateException if a run was already claimed for this
	 * application instance
	 */
	@NonNull
	public ShutdownResult run(@NonNull ShutdownCleanup shutdownCleanup,
			@NonNull ShutdownTrigger @NonNull... additionalShutdownTriggers) {
		ShutdownCleanup exactCleanup = requireNonNull(shutdownCleanup);
		Set<@NonNull ShutdownTrigger> triggerSnapshot =
				snapshot(additionalShutdownTriggers);
		return runPublic(exactCleanup, triggerSnapshot);
	}

	@NonNull
	private ShutdownResult runPublic(@Nullable ShutdownCleanup shutdownCleanup,
			@NonNull Set<@NonNull ShutdownTrigger> additionalShutdownTriggers) {
		claimRun();
		return runCore(SokletApplicationEnvironment.system(), shutdownCleanup,
				additionalShutdownTriggers).publicResult();
	}

	@NonNull
	InternalShutdownResult run(
			@NonNull SokletApplicationEnvironment environment) {
		return runInternal(environment, null,
				NO_ADDITIONAL_SHUTDOWN_TRIGGERS);
	}

	@NonNull
	InternalShutdownResult run(
			@NonNull SokletApplicationEnvironment environment,
			@NonNull ShutdownTrigger @NonNull... additionalShutdownTriggers) {
		Set<@NonNull ShutdownTrigger> triggerSnapshot =
				snapshot(additionalShutdownTriggers);
		return runInternal(environment, null, triggerSnapshot);
	}

	@NonNull
	InternalShutdownResult run(
			@NonNull SokletApplicationEnvironment environment,
			@NonNull ShutdownCleanup shutdownCleanup,
			@NonNull ShutdownTrigger @NonNull... additionalShutdownTriggers) {
		ShutdownCleanup exactCleanup = requireNonNull(shutdownCleanup);
		Set<@NonNull ShutdownTrigger> triggerSnapshot =
				snapshot(additionalShutdownTriggers);
		return runInternal(environment, exactCleanup, triggerSnapshot);
	}

	@NonNull
	private InternalShutdownResult runInternal(
			@NonNull SokletApplicationEnvironment environment,
			@Nullable ShutdownCleanup shutdownCleanup,
			@NonNull Set<@NonNull ShutdownTrigger> additionalShutdownTriggers) {
		claimRun();
		return runCore(environment, shutdownCleanup,
				additionalShutdownTriggers).result();
	}

	private void claimRun() {
		if (!this.runClaimed.compareAndSet(false, true))
			throw new IllegalStateException(
					"This SokletApplication lifecycle was already claimed");
	}

	@NonNull
	private InternalLifecycleCoreSnapshot runCore(
			@NonNull SokletApplicationEnvironment environment,
			@Nullable ShutdownCleanup shutdownCleanup,
			@NonNull Set<@NonNull ShutdownTrigger> additionalShutdownTriggers) {
		// Every validation in this block precedes the transport-identity commit
		// performed by the runtime factory.
		SokletApplicationEnvironment exactEnvironment =
				requireNonNull(environment);
		Set<@NonNull ShutdownTrigger> exactAdditionalShutdownTriggers =
				requireNonNull(additionalShutdownTriggers);
		LifecycleRuntimeServices services = exactEnvironment.services();
		SokletApplicationFinalization finalization =
				new SokletApplicationFinalization(shutdownCleanup, services,
						exactEnvironment.reporter());

		// Successful factory return is the one ownership commit.  A failure here
		// remains precommit and therefore creates no hook, cleanup, or report.
		SokletApplicationRuntime runtime = exactEnvironment.runtimeFactory()
				.create(this.config, services, finalization::publishCoreSnapshot);
		finalization.diagnosticsSupplier(runtime::diagnostics);
		finalization.terminalFailureClassifier(runtime::terminalFailure);
		Attempt attempt = new Attempt(runtime, finalization);
		Thread hook = null;
		boolean hookRegistered = false;
		SokletApplicationTriggerRegistration inputRegistration = null;
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

		if (!skipStart && exactAdditionalShutdownTriggers.contains(
				ShutdownTrigger.ENTER_KEY)) {
			try {
				inputRegistration = exactEnvironment.triggerRegistry()
						.register(() -> attempt.requestShutdown(
								TriggerSource.ENTER_KEY),
								message -> reportConfigurationUnsupported(
										exactEnvironment.processAccess(), message));
			} catch (Throwable failure) {
				processOwnershipFailure = failure;
				finalization.notePrimary(
						SokletApplicationPrimaryOutcome.PROCESS_OWNERSHIP_FAILURE,
						failure);
				attempt.requestShutdown(TriggerSource.PROCESS_OWNERSHIP_FAILURE);
				skipStart = true;
			}
		}

		if (!skipStart) {
			try {
				runtime.start();
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

		SokletApplicationFinalization.AwaitResult finalizationResult =
				finalization.awaitCompletion();
		interrupted |= finalizationResult.interrupted();

		Throwable processReleaseFailure = null;
		if (inputRegistration != null) {
			try {
				inputRegistration.unregister();
			} catch (Throwable failure) {
				processReleaseFailure = failure;
			}
		}
		if (hookRegistered) {
			try {
				exactEnvironment.processAccess().removeShutdownHook(
						requireNonNull(hook));
			} catch (IllegalStateException ignored) {
				// JVM shutdown won concurrently; the hook has already joined the
				// same bounded finalization completion.
			} catch (Throwable failure) {
				if (processReleaseFailure == null)
					processReleaseFailure = failure;
				else if (!sameInstance(processReleaseFailure, failure))
					processReleaseFailure.addSuppressed(failure);
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

	private void reportConfigurationUnsupported(
			@NonNull LifecycleProcessAccess processAccess,
			@NonNull String message) {
		LifecycleProcessAccess exactProcessAccess = requireNonNull(processAccess);
		String exactMessage = requireNonNull(message);
		try {
			this.config.getAggregateLifecycleObserver().didReceiveLogEvent(
					LogEvent.with(LogEventType.CONFIGURATION_UNSUPPORTED,
							exactMessage).build());
		} catch (Throwable observerFailure) {
			try {
				exactProcessAccess.reportConfigurationWarning(exactMessage);
			} catch (Throwable ignored) {
				// A warning channel cannot control process ownership.
			}
		}
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

	@NonNull
	private static Set<@NonNull ShutdownTrigger> snapshot(
			@NonNull ShutdownTrigger @NonNull... additionalShutdownTriggers) {
		ShutdownTrigger[] exactTriggers =
				requireNonNull(additionalShutdownTriggers);
		EnumSet<ShutdownTrigger> triggerSnapshot =
				EnumSet.noneOf(ShutdownTrigger.class);
		for (ShutdownTrigger shutdownTrigger : exactTriggers)
			triggerSnapshot.add(requireNonNull(shutdownTrigger));
		return Collections.unmodifiableSet(triggerSnapshot);
	}

	private enum TriggerSource {
		HOOK,
		ENTER_KEY,
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
		@NonNull SokletApplicationTriggerRegistry triggerRegistry,
		@NonNull LifecycleTerminalReporter reporter,
		@NonNull SokletApplicationRuntimeFactory runtimeFactory,
		@NonNull SokletApplicationHookFactory hookFactory) {
	SokletApplicationEnvironment {
		requireNonNull(services);
		requireNonNull(processAccess);
		requireNonNull(triggerRegistry);
		requireNonNull(reporter);
		requireNonNull(runtimeFactory);
		requireNonNull(hookFactory);
	}

	@NonNull
	static SokletApplicationEnvironment system() {
		return new SokletApplicationEnvironment(
				LifecycleRuntimeServices.system(), SokletApplication.SYSTEM_PROCESS,
				SokletApplication.SYSTEM_INPUT,
				DefaultLifecycleTerminalReporter.system(),
				DirectSokletApplicationRuntime::create,
				(name, task) -> new Thread(task, name));
	}
}

interface SokletApplicationTriggerRegistry {
	@NonNull
	SokletApplicationTriggerRegistration register(
			@NonNull Runnable shutdownIntent);

	@NonNull
	default SokletApplicationTriggerRegistration register(
			@NonNull Runnable shutdownIntent,
			@NonNull Consumer<@NonNull String> configurationWarningSink) {
		requireNonNull(configurationWarningSink);
		return register(requireNonNull(shutdownIntent));
	}
}

@FunctionalInterface
interface SokletApplicationTriggerRegistration {
	void unregister();
}

/** Separate process-global stdin owner; legacy KeypressManager is not reused. */
@ThreadSafe
final class SokletApplicationInputManager
		implements SokletApplicationTriggerRegistry {
	@FunctionalInterface
	interface DaemonLauncher {
		void launch(@NonNull String name, @NonNull Runnable task);
	}

	@NonNull
	private final LifecycleProcessAccess processAccess;
	@NonNull
	private final DaemonLauncher launcher;
	@NonNull
	private final Set<Registration> registrations;
	@NonNull
	private final Object listenerMonitor;
	@NonNull
	private final AtomicBoolean listenerStarted;
	private long nextListenerGeneration;
	private long activeListenerGeneration;
	private long registrationEpoch;
	@Nullable
	private InputStream latestInput;
	@Nullable
	private InputStream pendingCarriageReturnInput;

	SokletApplicationInputManager(@NonNull LifecycleProcessAccess processAccess,
			@NonNull DaemonLauncher launcher) {
		this.processAccess = requireNonNull(processAccess);
		this.launcher = requireNonNull(launcher);
		this.registrations = new CopyOnWriteArraySet<>();
		this.listenerMonitor = new Object();
		this.listenerStarted = new AtomicBoolean();
	}

	@NonNull
	@Override
	public SokletApplicationTriggerRegistration register(
			@NonNull Runnable shutdownIntent) {
		return register(requireNonNull(shutdownIntent),
				this.processAccess::reportConfigurationWarning);
	}

	@NonNull
	@Override
	public SokletApplicationTriggerRegistration register(
			@NonNull Runnable shutdownIntent,
			@NonNull Consumer<@NonNull String> configurationWarningSink) {
		Registration registration = new Registration(
				requireNonNull(shutdownIntent),
				requireNonNull(configurationWarningSink));
		Optional<InputStream> input = this.processAccess.standardInput();
		if (input.isEmpty()) {
			registration.warnOnce(
					"Ignoring ENTER_KEY shutdown because stdin is unavailable");
			return () -> { };
		}
		InputStream exactInput = input.orElseThrow();
		synchronized (this.listenerMonitor) {
			this.registrations.add(registration);
			this.registrationEpoch++;
			this.latestInput = exactInput;
			if (!this.listenerStarted.get()) {
				try {
					launchListenerWhileLocked(exactInput);
				} catch (RuntimeException | Error launchFailure) {
					this.registrations.remove(registration);
					throw launchFailure;
				}
			}
		}
		AtomicBoolean registered = new AtomicBoolean(true);
		return () -> {
			if (registered.compareAndSet(true, false)) {
				synchronized (this.listenerMonitor) {
					this.registrations.remove(registration);
				}
			}
		};
	}

	boolean isListenerStarted() {
		return this.listenerStarted.get();
	}

	int registrationCount() {
		return this.registrations.size();
	}

	private void launchListenerWhileLocked(@NonNull InputStream input) {
		if (!Thread.holdsLock(this.listenerMonitor))
			throw new IllegalStateException("The listener monitor is required");
		long generation = ++this.nextListenerGeneration;
		this.activeListenerGeneration = generation;
		this.listenerStarted.set(true);
		try {
			this.launcher.launch("soklet-application-enter-key",
					() -> runListener(requireNonNull(input), generation));
		} catch (RuntimeException | Error launchFailure) {
			if (this.activeListenerGeneration == generation) {
				this.activeListenerGeneration = 0L;
				this.listenerStarted.set(false);
			}
			throw launchFailure;
		}
	}

	private void runListener(@NonNull InputStream input, long generation) {
		String warning = null;
		List<Registration> warningRegistrations = List.of();
		long observedRegistrationEpoch = 0L;
		try {
			for (;;) {
				synchronized (this.listenerMonitor) {
					observedRegistrationEpoch = this.registrationEpoch;
					if (this.registrations.isEmpty())
						return;
				}
				// Never add decoder/read-ahead buffers to borrowed process stdin.
				// Unregistration cannot portably cancel this one blocking byte read
				// without closing stdin; recheck before starting any further read.
				int value = requireNonNull(input).read();
				List<Registration> snapshot;
				synchronized (this.listenerMonitor) {
					// A CR-triggered generation intentionally performs no read-ahead.
					// Consume its LF only when the next generation reads that same input.
					boolean previousCarriageReturn = sameInstance(this.pendingCarriageReturnInput, input);
					this.pendingCarriageReturnInput = null;
					observedRegistrationEpoch = this.registrationEpoch;
					if (this.registrations.isEmpty())
						return;
					if (previousCarriageReturn && value == '\n')
						continue;
					if (value < 0) {
						warning = "Ignoring ENTER_KEY shutdown because stdin reached EOF";
						warningRegistrations = List.copyOf(this.registrations);
						break;
					}
					if (value != '\n' && value != '\r')
						continue;
					if (value == '\r')
						this.pendingCarriageReturnInput = input;
					snapshot = List.copyOf(this.registrations);
				}
				broadcastShutdownIntent(snapshot);
				// Only registrations created during delivery need a fresh listener.
				break;
			}
		} catch (IOException | RuntimeException failure) {
			warning = "Ignoring ENTER_KEY shutdown because stdin became unusable";
			synchronized (this.listenerMonitor) {
				observedRegistrationEpoch = this.registrationEpoch;
				warningRegistrations = List.copyOf(this.registrations);
			}
		} finally {
			retireListener(generation, observedRegistrationEpoch);
		}
		if (warning != null) {
			for (Registration registration : warningRegistrations)
				registration.warnOnce(warning);
		}
	}

	private void retireListener(long generation,
			long observedRegistrationEpoch) {
		synchronized (this.listenerMonitor) {
			if (this.activeListenerGeneration != generation)
				return;
			this.activeListenerGeneration = 0L;
			this.listenerStarted.set(false);
			if (this.registrations.isEmpty()
					|| this.registrationEpoch <= observedRegistrationEpoch
					|| this.latestInput == null)
				return;
			try {
				launchListenerWhileLocked(this.latestInput);
			} catch (RuntimeException | Error ignored) {
				// No caller exists for an exit-time handoff failure. A later
				// registration may retry, and the process warning remains bounded.
			}
		}
	}

	private void broadcastShutdownIntent(@NonNull List<Registration> snapshot) {
		for (Registration registration : snapshot) {
			try {
				registration.shutdownIntent().run();
			} catch (Throwable ignored) {
				// Every other runner still receives nonblocking intent.
			}
		}
	}

	private static final class Registration {
		@NonNull
		private final Runnable shutdownIntent;
		@NonNull
		private final Consumer<@NonNull String> configurationWarningSink;
		@NonNull
		private final AtomicBoolean warningEmitted;

		private Registration(@NonNull Runnable shutdownIntent,
				@NonNull Consumer<@NonNull String> configurationWarningSink) {
			this.shutdownIntent = requireNonNull(shutdownIntent);
			this.configurationWarningSink = requireNonNull(
					configurationWarningSink);
			this.warningEmitted = new AtomicBoolean();
		}

		@NonNull
		private Runnable shutdownIntent() {
			return this.shutdownIntent;
		}

		private void warnOnce(@NonNull String message) {
			if (!this.warningEmitted.compareAndSet(false, true))
				return;
			try {
				this.configurationWarningSink.accept(requireNonNull(message));
			} catch (Throwable ignored) {
				// A warning channel cannot control process ownership.
			}
		}
	}

	static void launchDaemon(@NonNull String name, @NonNull Runnable task) {
		Thread listener = new Thread(requireNonNull(task), requireNonNull(name));
		listener.setDaemon(true);
		listener.start();
	}
}

/** Actual JVM process seam used only by the package-private runner. */
final class SystemLifecycleProcessAccess implements LifecycleProcessAccess {
	@NonNull
	@Override
	public Optional<InputStream> standardInput() {
		return Optional.ofNullable(System.in);
	}

	@Override
	public void addShutdownHook(@NonNull Thread hook) {
		Runtime.getRuntime().addShutdownHook(requireNonNull(hook));
	}

	@Override
	public boolean removeShutdownHook(@NonNull Thread hook) {
		return Runtime.getRuntime().removeShutdownHook(requireNonNull(hook));
	}

	@Override
	public void reportConfigurationWarning(@NonNull String message) {
		System.err.println(DefaultLifecycleTerminalReporter.escapeAndCap(
				requireNonNull(message), 512));
	}
}
