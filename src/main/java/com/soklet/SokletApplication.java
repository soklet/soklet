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
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/** Configured, one-shot standalone-process adapter for one Soklet lifecycle. */
@ThreadSafe
public final class SokletApplication {
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
	private final Set<@NonNull ShutdownTrigger> additionalShutdownTriggers;
	@Nullable
	private final SokletApplicationCleanupConfiguration cleanupConfiguration;
	@NonNull
	private final AtomicBoolean runClaimed;

	private SokletApplication(@NonNull Builder builder) {
		Builder exactBuilder = requireNonNull(builder);
		this.config = exactBuilder.config;
		this.additionalShutdownTriggers = Collections.unmodifiableSet(
				exactBuilder.additionalShutdownTriggers.isEmpty()
						? EnumSet.noneOf(ShutdownTrigger.class)
						: EnumSet.copyOf(exactBuilder.additionalShutdownTriggers));
		this.cleanupConfiguration = cleanupConfiguration(
				exactBuilder.shutdownCleanupTimeout,
				exactBuilder.shutdownCleanup);
		this.runClaimed = new AtomicBoolean();
	}

	/**
	 * Runs one standalone Soklet lifecycle with the default runner settings.
	 *
	 * @param config the one-shot Soklet configuration
	 * @return the exact immutable lifecycle result
	 * @throws SokletStartupException if startup does not reach readiness
	 * @throws SokletTerminatedUnexpectedlyException if a transport terminates
	 * unexpectedly after readiness
	 * @throws ShutdownIncompleteException if shutdown cannot be proven complete
	 */
	@NonNull
	public static ShutdownResult run(@NonNull SokletConfig config) {
		return withConfig(config).build().run();
	}

	/**
	 * Runs one standalone Soklet lifecycle with the default settings plus the
	 * supplied runner-scoped shutdown triggers.
	 *
	 * @param config the one-shot Soklet configuration
	 * @param additionalShutdownTriggers non-null additional shutdown triggers;
	 * each element must be non-null
	 * @return the exact immutable lifecycle result
	 * @throws SokletStartupException if startup does not reach readiness
	 * @throws SokletTerminatedUnexpectedlyException if a transport terminates
	 * unexpectedly after readiness
	 * @throws ShutdownIncompleteException if shutdown cannot be proven complete
	 */
	@NonNull
	public static ShutdownResult run(@NonNull SokletConfig config,
			@NonNull ShutdownTrigger @NonNull... additionalShutdownTriggers) {
		requireNonNull(additionalShutdownTriggers);
		Builder builder = withConfig(config);
		for (ShutdownTrigger shutdownTrigger : additionalShutdownTriggers)
			builder.addShutdownTrigger(requireNonNull(shutdownTrigger));
		return builder.build().run();
	}

	/**
	 * Vends an application builder primed with the required one-shot Soklet
	 * configuration.
	 *
	 * @param config the one-shot Soklet configuration
	 * @return a builder for one standalone application lifecycle
	 */
	@NonNull
	public static Builder withConfig(@NonNull SokletConfig config) {
		return new Builder(config);
	}

	/**
	 * Runs this configured application lifecycle and its shared, bounded
	 * finalization sequence. A configured cleanup action is eligible only after
	 * the core shutdown result is complete. This method may be invoked exactly
	 * once on each application instance.
	 *
	 * @return the exact immutable lifecycle result
	 * @throws SokletStartupException if startup does not reach readiness
	 * @throws SokletTerminatedUnexpectedlyException if a transport terminates
	 * unexpectedly after readiness
	 * @throws ShutdownIncompleteException if shutdown cannot be proven complete
	 * @throws SokletApplicationCleanupException if an eligible cleanup fails or
	 * exceeds its configured deadline
	 * @throws IllegalStateException if a run was already claimed for this
	 * application instance
	 */
	@NonNull
	public ShutdownResult run() {
		claimRun();
		return runCore(SokletApplicationEnvironment.system()).publicResult();
	}

	@NonNull
	InternalShutdownResult run(
			@NonNull SokletApplicationEnvironment environment) {
		claimRun();
		return runCore(environment).result();
	}

	private void claimRun() {
		if (!this.runClaimed.compareAndSet(false, true))
			throw new IllegalStateException(
					"This SokletApplication lifecycle was already claimed");
	}

	@NonNull
	private InternalLifecycleCoreSnapshot runCore(
			@NonNull SokletApplicationEnvironment environment) {
		// Every validation in this block precedes the transport-identity commit
		// performed by the runtime factory.
		SokletApplicationEnvironment exactEnvironment =
				requireNonNull(environment);
		LifecycleRuntimeServices services = exactEnvironment.services();
		SokletApplicationFinalization finalization =
				new SokletApplicationFinalization(this.cleanupConfiguration, services,
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

		if (!skipStart && this.additionalShutdownTriggers.contains(
				ShutdownTrigger.ENTER_KEY)) {
			try {
				inputRegistration = exactEnvironment.triggerRegistry()
						.register(() -> attempt.requestShutdown(
								TriggerSource.ENTER_KEY));
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
				else if (processReleaseFailure != failure)
					processReleaseFailure.addSuppressed(failure);
			}
		}

		if (interrupted)
			Thread.currentThread().interrupt();

		InternalShutdownCleanupOutcome cleanupOutcome =
				finalizationResult.cleanupOutcome();
		SokletApplicationCleanupException cleanupFailure =
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
		if (exactPrimary == exactSecondary)
			return;
		for (Throwable existing : exactPrimary.getSuppressed())
			if (existing == exactSecondary)
				return;
		exactPrimary.addSuppressed(exactSecondary);
	}

	@Nullable
	private static SokletApplicationCleanupConfiguration cleanupConfiguration(
			@Nullable Duration timeout, @Nullable ShutdownCleanup cleanup) {
		if (timeout == null && cleanup == null)
			return null;
		if (timeout == null || cleanup == null)
			throw new IllegalArgumentException(
					"Shutdown cleanup and its timeout must be configured together");
		final long timeoutNanos;
		try {
			timeoutNanos = timeout.toNanos();
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException(
					"Shutdown cleanup timeout exceeds signed nanoseconds",
					exception);
		}
		if (timeoutNanos <= 0L)
			throw new IllegalArgumentException(
					"Shutdown cleanup timeout must be greater than zero");
		return new SokletApplicationCleanupConfiguration(timeout, cleanup);
	}

	/** Builds one configured, one-shot {@link SokletApplication}. */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final SokletConfig config;
		@NonNull
		private final EnumSet<ShutdownTrigger> additionalShutdownTriggers;
		@Nullable
		private ShutdownCleanup shutdownCleanup;
		@Nullable
		private Duration shutdownCleanupTimeout;

		private Builder(@NonNull SokletConfig config) {
			this.config = requireNonNull(config);
			this.additionalShutdownTriggers =
					EnumSet.noneOf(ShutdownTrigger.class);
		}

		/**
		 * Adds a runner-scoped shutdown trigger.
		 *
		 * @param shutdownTrigger the shutdown trigger to add
		 * @return this builder
		 */
		@NonNull
		public Builder addShutdownTrigger(
				@NonNull ShutdownTrigger shutdownTrigger) {
			this.additionalShutdownTriggers.add(
					requireNonNull(shutdownTrigger));
			return this;
		}

		/**
		 * Configures the built application's cleanup action. The action is invoked
		 * at most once, only after a complete core result, and must finish
		 * synchronously within the supplied positive duration. Zero, negative, or
		 * durations that cannot be represented as signed nanoseconds are rejected
		 * by {@link #build()}.
		 *
		 * @param timeout explicit cleanup deadline budget
		 * @param cleanup cleanup action for ingress-exclusive application resources
		 * @return this builder
		 */
		@NonNull
		public Builder afterCompleteShutdown(@NonNull Duration timeout,
				@NonNull ShutdownCleanup cleanup) {
			this.shutdownCleanupTimeout = requireNonNull(timeout);
			this.shutdownCleanup = requireNonNull(cleanup);
			return this;
		}

		/**
		 * Builds one configured application lifecycle.
		 *
		 * @return a new one-shot application
		 * @throws IllegalArgumentException if the cleanup timeout is non-positive
		 * or exceeds signed-nanosecond range
		 */
		@NonNull
		public SokletApplication build() {
			return new SokletApplication(this);
		}
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

@Immutable
final class SokletApplicationCleanupConfiguration {
	@NonNull
	private final Duration timeout;
	@NonNull
	private final ShutdownCleanup cleanup;

	SokletApplicationCleanupConfiguration(@NonNull Duration timeout,
			@NonNull ShutdownCleanup cleanup) {
		this.timeout = requireNonNull(timeout);
		this.cleanup = requireNonNull(cleanup);
	}

	@NonNull
	Duration timeout() {
		return this.timeout;
	}

	@NonNull
	ShutdownCleanup cleanup() {
		return this.cleanup;
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
	private final Set<Runnable> registrations;
	@NonNull
	private final Object listenerMonitor;
	@NonNull
	private final AtomicBoolean listenerStarted;
	@NonNull
	private final AtomicBoolean warningEmitted;
	private long nextListenerGeneration;
	private long activeListenerGeneration;
	private long registrationEpoch;
	@Nullable
	private InputStream latestInput;

	SokletApplicationInputManager(@NonNull LifecycleProcessAccess processAccess,
			@NonNull DaemonLauncher launcher) {
		this.processAccess = requireNonNull(processAccess);
		this.launcher = requireNonNull(launcher);
		this.registrations = new CopyOnWriteArraySet<>();
		this.listenerMonitor = new Object();
		this.listenerStarted = new AtomicBoolean();
		this.warningEmitted = new AtomicBoolean();
	}

	@NonNull
	@Override
	public SokletApplicationTriggerRegistration register(
			@NonNull Runnable shutdownIntent) {
		Runnable exactIntent = requireNonNull(shutdownIntent);
		Optional<InputStream> input = this.processAccess.standardInput();
		if (input.isEmpty()) {
			warnOnce("Ignoring ENTER_KEY shutdown because stdin is unavailable");
			return () -> { };
		}
		InputStream exactInput = input.orElseThrow();
		synchronized (this.listenerMonitor) {
			this.registrations.add(exactIntent);
			this.registrationEpoch++;
			this.latestInput = exactInput;
			if (!this.listenerStarted.get()) {
				try {
					launchListenerWhileLocked(exactInput);
				} catch (RuntimeException | Error launchFailure) {
					this.registrations.remove(exactIntent);
					throw launchFailure;
				}
			}
		}
		AtomicBoolean registered = new AtomicBoolean(true);
		return () -> {
			if (registered.compareAndSet(true, false))
				this.registrations.remove(exactIntent);
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
		long observedRegistrationEpoch = registrationEpoch();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				new NonClosingInputStream(requireNonNull(input)),
				StandardCharsets.UTF_8))) {
			for (;;) {
				observedRegistrationEpoch = registrationEpoch();
				String line = reader.readLine();
				if (line == null) {
					warning = "Ignoring ENTER_KEY shutdown because stdin reached EOF";
					break;
				}
				broadcastShutdownIntent();
			}
		} catch (IOException | RuntimeException failure) {
			warning = "Ignoring ENTER_KEY shutdown because stdin became unusable";
		} finally {
			retireListener(generation, observedRegistrationEpoch);
		}
		warnOnce(requireNonNull(warning));
	}

	/** Closes decoder buffers without taking ownership of process stdin. */
	private static final class NonClosingInputStream extends FilterInputStream {
		private NonClosingInputStream(@NonNull InputStream input) {
			super(requireNonNull(input));
		}

		@Override
		public void close() {
			// The process owns stdin; a retired listener must leave it available.
		}
	}

	private long registrationEpoch() {
		synchronized (this.listenerMonitor) {
			return this.registrationEpoch;
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

	private void broadcastShutdownIntent() {
		List<Runnable> snapshot = List.copyOf(this.registrations);
		for (Runnable shutdownIntent : snapshot) {
			try {
				shutdownIntent.run();
			} catch (Throwable ignored) {
				// Every other runner still receives nonblocking intent.
			}
		}
	}

	private void warnOnce(@NonNull String message) {
		if (!this.warningEmitted.compareAndSet(false, true))
			return;
		try {
			this.processAccess.reportConfigurationWarning(requireNonNull(message));
		} catch (Throwable ignored) {
			// A warning channel cannot control process ownership.
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
		return Optional.of(System.in);
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
