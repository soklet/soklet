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

import com.soklet.annotation.GET;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/** Production-bridge acceptance coverage for frozen terminal diagnostics. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class SokletApplicationDiagnosticsIntegrationTests {
	@NonNull
	private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(1);
	private static final int NESTED_HTTP_MEMBER_COUNT = 18;

	@Test
	void blockedFrameworkSetupSynthesizesFrameworkDiagnosticsAndSkipsCleanup()
			throws Exception {
		BlockingResourceMethodResolver resolver =
				new BlockingResourceMethodResolver(
						ResourceMethodResolver.fromClasses(
								Set.of(DiagnosticsResource.class)));
		ObservedLauncher launcher = new ObservedLauncher();
		LifecycleWorkers workers = new LifecycleWorkers(launcher);
		RecordingTriggerRegistry triggers = new RecordingTriggerRegistry();
		RecordingReporter reporter = new RecordingReporter();
		AtomicInteger cleanupCalls = new AtomicInteger();
		SokletConfig config = SokletConfig.withHttpServer(
				HttpServer.withPort(0).build())
				.resourceMethodResolver(resolver)
				.internalLifecyclePolicy(immediateShutdownPolicy())
				.build();
		RunnerCall call = startRunner(application(config),
				environment(workers, triggers, reporter), cleanup(cleanupCalls),
				ShutdownTrigger.ENTER_KEY);

		try {
			Assertions.assertTrue(resolver.awaitEntered(),
					"Framework setup did not reach the blocking resolver");
			triggers.trigger();
			call.await();
			SokletApplicationTerminalSnapshot snapshot = reporter.awaitSnapshot();
			InternalShutdownResult result = snapshot.coreSnapshot().result();

			ShutdownIncompleteException failure = Assertions.assertInstanceOf(
					ShutdownIncompleteException.class, call.failure());
			Assertions.assertSame(result, failure.getInternalShutdownResult());
			Assertions.assertNull(call.result());
			assertSkippedCleanup(snapshot, cleanupCalls, workers);
			Assertions.assertEquals(InternalShutdownDisposition.INCOMPLETE,
					result.disposition());
			InternalLifecycleComponentShutdownResult setup = result.participantResult(
					InternalLifecycleComponentType.FRAMEWORK).orElseThrow();
			Assertions.assertTrue(setup.residualActivity().contains(
					InternalResidualActivityType.LIFECYCLE_CALL));
			Integer retainedCalls = result.retentionSummary().orElseThrow()
					.counts().get(InternalResidualActivityType.LIFECYCLE_CALL);
			Assertions.assertNotNull(retainedCalls);
			Assertions.assertTrue(retainedCalls >= 1,
					"The frozen framework setup call must remain retained");

			SokletApplicationParticipantDiagnostics diagnostics = snapshot
					.coreDiagnostics().participantDiagnostics()
					.get(InternalLifecycleComponentType.FRAMEWORK);
			Assertions.assertNotNull(diagnostics);
			Assertions.assertEquals(InternalTerminationAuthority.FRAMEWORK_PROVEN,
					diagnostics.authority());
			Assertions.assertEquals(1, diagnostics.memberCount());
			Assertions.assertFalse(diagnostics.truncated());
		} finally {
			triggers.triggerIfRegistered();
			resolver.release();
			Assertions.assertTrue(resolver.awaitReturned(),
					"The released framework setup call did not return");
			Assertions.assertTrue(launcher.awaitCompleted(
					"soklet-framework-setup"),
					"The released framework setup worker did not complete");
			launcher.awaitTermination();
		}
	}

	@Test
	void blockedNestedCustomHttpAttachProjectsBoundedTransportDiagnostics()
			throws Exception {
		InternalTransportIdentity identity = InternalTransportIdentity.create();
		CountDownLatch leafAttachEntered = new CountDownLatch(1);
		CountDownLatch releaseLeafAttach = new CountDownLatch(1);
		CountDownLatch outerAttachReturned = new CountDownLatch(1);
		NestedHttpEndpoint endpoint = NestedHttpEndpoint.blockingLeaf(identity,
				leafAttachEntered, releaseLeafAttach);
		for (int member = 2; member < NESTED_HTTP_MEMBER_COUNT; member++)
			endpoint = NestedHttpEndpoint.owning(identity, endpoint, null);
		endpoint = NestedHttpEndpoint.owning(identity, endpoint,
				outerAttachReturned);

		ObservedLauncher launcher = new ObservedLauncher();
		LifecycleWorkers workers = new LifecycleWorkers(launcher);
		RecordingTriggerRegistry triggers = new RecordingTriggerRegistry();
		RecordingReporter reporter = new RecordingReporter();
		AtomicInteger cleanupCalls = new AtomicInteger();
		SokletConfig config = SokletConfig.withHttpServer(endpoint)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(DiagnosticsResource.class)))
				.internalLifecyclePolicy(immediateShutdownPolicy())
				.build();
		RunnerCall call = startRunner(application(config),
				environment(workers, triggers, reporter), cleanup(cleanupCalls),
				ShutdownTrigger.ENTER_KEY);

		try {
			Assertions.assertTrue(leafAttachEntered.await(5, TimeUnit.SECONDS),
					"Nested HTTP attachment did not reach its blocking leaf");
			triggers.trigger();
			call.await();
			SokletApplicationTerminalSnapshot snapshot = reporter.awaitSnapshot();
			InternalShutdownResult result = snapshot.coreSnapshot().result();

			ShutdownIncompleteException failure = Assertions.assertInstanceOf(
					ShutdownIncompleteException.class, call.failure());
			Assertions.assertSame(result, failure.getInternalShutdownResult());
			Assertions.assertNull(call.result());
			assertSkippedCleanup(snapshot, cleanupCalls, workers);
			Assertions.assertEquals(InternalShutdownDisposition.INCOMPLETE,
					result.disposition());
			Assertions.assertEquals(1L, outerAttachReturned.getCount(),
					"Terminal freeze must precede the configured attachment return "
							+ "and commit");
			InternalLifecycleComponentShutdownResult http = result.participantResult(
					InternalLifecycleComponentType.HTTP).orElseThrow();
			Assertions.assertTrue(http.residualActivity().contains(
					InternalResidualActivityType.LIFECYCLE_CALL));

			SokletApplicationParticipantDiagnostics diagnostics = snapshot
					.coreDiagnostics().participantDiagnostics()
					.get(InternalLifecycleComponentType.HTTP);
			Assertions.assertNotNull(diagnostics);
			Assertions.assertEquals(InternalTerminationAuthority.TRANSPORT_ATTESTED,
					diagnostics.authority());
			Assertions.assertEquals(NESTED_HTTP_MEMBER_COUNT,
					diagnostics.memberCount());
			Assertions.assertEquals(0, diagnostics.failedMembers());
			Assertions.assertEquals(0, diagnostics.provenMembers());
			Assertions.assertTrue(diagnostics.truncated(),
					"A real termination group larger than its diagnostic bound must "
							+ "report truncation");
		} finally {
			triggers.triggerIfRegistered();
			releaseLeafAttach.countDown();
			Assertions.assertTrue(outerAttachReturned.await(5, TimeUnit.SECONDS),
					"The released outer HTTP attachment did not return");
			Assertions.assertTrue(launcher.awaitCompleted("soklet-attach-http"),
					"The released HTTP attachment worker did not complete");
			launcher.awaitTermination();
		}
	}

	private static void assertSkippedCleanup(
			@NonNull SokletApplicationTerminalSnapshot snapshot,
			@NonNull AtomicInteger cleanupCalls,
			@NonNull LifecycleWorkers workers) {
		InternalShutdownCleanupOutcome cleanup = requireNonNull(snapshot)
				.cleanupOutcome();
		Assertions.assertEquals(
				InternalShutdownCleanupDisposition.SKIPPED_INCOMPLETE_SHUTDOWN,
				cleanup.disposition());
		Assertions.assertEquals(Optional.of(CLEANUP_TIMEOUT),
				cleanup.configuredTimeout());
		Assertions.assertTrue(cleanup.failure().isEmpty());
		Assertions.assertFalse(cleanup.workerMayRemain());
		Assertions.assertEquals(0, requireNonNull(cleanupCalls).get());
		Assertions.assertEquals(0, requireNonNull(workers).created(
				LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
		Assertions.assertEquals(0, workers.active(
				LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
	}

	@NonNull
	private static SokletApplication application(@NonNull SokletConfig config) {
		return SokletApplication.fromConfig(config);
	}

	@NonNull
	private static ShutdownCleanup cleanup(
			@NonNull AtomicInteger cleanupCalls) {
		return ShutdownCleanup.fromTimeoutAndAction(CLEANUP_TIMEOUT,
				result -> requireNonNull(cleanupCalls).incrementAndGet());
	}

	@NonNull
	private static InternalLifecyclePolicy immediateShutdownPolicy() {
		return new InternalLifecyclePolicy(Optional.empty(), Duration.ZERO,
				Duration.ZERO, Duration.ZERO);
	}

	@NonNull
	private static SokletApplicationEnvironment environment(
			@NonNull LifecycleWorkers workers,
			@NonNull RecordingTriggerRegistry triggers,
			@NonNull RecordingReporter reporter) {
		LifecycleRuntimeServices services = new LifecycleRuntimeServices(
				NanoClock.system(), requireNonNull(workers));
		return new SokletApplicationEnvironment(services,
				new RecordingProcessAccess(), requireNonNull(triggers),
				requireNonNull(reporter), DirectSokletApplicationRuntime::create,
				(name, task) -> new Thread(task, name));
	}

	@NonNull
	private static RunnerCall startRunner(@NonNull SokletApplication application,
			@NonNull SokletApplicationEnvironment environment,
			@NonNull ShutdownCleanup cleanup,
			@NonNull ShutdownTrigger... additionalShutdownTriggers) {
		RunnerCall call = new RunnerCall();
		Thread thread = new Thread(() -> {
			try {
				call.result.set(application.run(environment, cleanup,
						additionalShutdownTriggers));
			} catch (Throwable failure) {
				call.failure.set(failure);
			} finally {
				call.done.countDown();
			}
		}, "soklet-application-diagnostics-runner");
		thread.setDaemon(true);
		thread.start();
		return call;
	}

	private static void awaitIgnoringInterrupts(@NonNull CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try {
				requireNonNull(latch).await();
				break;
			} catch (InterruptedException ignored) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	private static final class BlockingResourceMethodResolver
			implements ResourceMethodResolver {
		@NonNull
		private final ResourceMethodResolver delegate;
		@NonNull
		private final CountDownLatch entered = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch release = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch returned = new CountDownLatch(1);

		private BlockingResourceMethodResolver(
				@NonNull ResourceMethodResolver delegate) {
			this.delegate = requireNonNull(delegate);
		}

		@Override
		@NonNull
		public Optional<ResourceMethod> resourceMethodForRequest(
				@NonNull Request request, @NonNull ServerType serverType) {
			return this.delegate.resourceMethodForRequest(request, serverType);
		}

		@Override
		@NonNull
		public Set<@NonNull ResourceMethod> getResourceMethods() {
			this.entered.countDown();
			awaitIgnoringInterrupts(this.release);
			try {
				return this.delegate.getResourceMethods();
			} finally {
				this.returned.countDown();
			}
		}

		boolean awaitEntered() throws InterruptedException {
			return this.entered.await(5, TimeUnit.SECONDS);
		}

		void release() {
			this.release.countDown();
		}

		boolean awaitReturned() throws InterruptedException {
			return this.returned.await(5, TimeUnit.SECONDS);
		}
	}

	private static final class NestedHttpEndpoint
			implements HttpServer, InternalHttpTransportEndpoint {
		@NonNull
		private static final InternalTransportRuntime INERT_RUNTIME =
				new InternalTransportRuntime() {
					@Override
					public void start(@NonNull StartupContext context) { }

					@Override
					public void quiesce(@NonNull ShutdownContext context) { }

					@Override
					public void force(@NonNull ShutdownContext context) { }
				};

		@NonNull
		private final InternalTransportIdentity identity;
		@Nullable
		private final NestedHttpEndpoint delegate;
		@Nullable
		private final CountDownLatch attachEntered;
		@Nullable
		private final CountDownLatch releaseAttach;
		@Nullable
		private final CountDownLatch attachReturned;

		private NestedHttpEndpoint(@NonNull InternalTransportIdentity identity,
				@Nullable NestedHttpEndpoint delegate,
				@Nullable CountDownLatch attachEntered,
				@Nullable CountDownLatch releaseAttach,
				@Nullable CountDownLatch attachReturned) {
			this.identity = requireNonNull(identity);
			this.delegate = delegate;
			this.attachEntered = attachEntered;
			this.releaseAttach = releaseAttach;
			this.attachReturned = attachReturned;
		}

		@NonNull
		static NestedHttpEndpoint blockingLeaf(
				@NonNull InternalTransportIdentity identity,
				@NonNull CountDownLatch attachEntered,
				@NonNull CountDownLatch releaseAttach) {
			return new NestedHttpEndpoint(identity, null, attachEntered,
					releaseAttach, null);
		}

		@NonNull
		static NestedHttpEndpoint owning(
				@NonNull InternalTransportIdentity identity,
				@NonNull NestedHttpEndpoint delegate,
				@Nullable CountDownLatch attachReturned) {
			return new NestedHttpEndpoint(identity, delegate, null, null,
					attachReturned);
		}

		@Override
		@NonNull
		public InternalTransportIdentity identity() {
			return this.identity;
		}

		@Override
		@NonNull
		public InternalTransportRuntime attach(
				@NonNull InternalTransportAttachmentContext<RequestHandler> context,
				@NonNull StartupContext startupContext) {
			try {
				if (this.delegate != null)
					return context.attachLifecycleOwningDelegate(this.delegate,
							context.requestHandler()).runtime();
				requireNonNull(this.attachEntered).countDown();
				awaitIgnoringInterrupts(requireNonNull(this.releaseAttach));
				return INERT_RUNTIME;
			} finally {
				if (this.attachReturned != null)
					this.attachReturned.countDown();
			}
		}

		@Override
		public void start() {
			throw new AssertionError("Direct endpoint startup uses its runtime");
		}

		@Override
		public void stop() { }

		@Override
		@NonNull
		public Boolean isStarted() {
			return false;
		}

		@Override
		public void initialize(@NonNull SokletConfig sokletConfig,
				@NonNull RequestHandler requestHandler) {
			throw new AssertionError("Direct endpoint attachment uses attach(...)");
		}
	}

	private static final class RecordingProcessAccess
			implements LifecycleProcessAccess {
		@NonNull
		private final AtomicReference<Thread> hook = new AtomicReference<>();

		@Override
		@NonNull
		public Optional<InputStream> standardInput() {
			return Optional.empty();
		}

		@Override
		public void addShutdownHook(@NonNull Thread hook) {
			if (!this.hook.compareAndSet(null, requireNonNull(hook)))
				throw new AssertionError("Shutdown hook was registered twice");
		}

		@Override
		public boolean removeShutdownHook(@NonNull Thread hook) {
			Assertions.assertSame(this.hook.get(), requireNonNull(hook));
			return this.hook.compareAndSet(hook, null);
		}

		@Override
		public void reportConfigurationWarning(@NonNull String message) {
			throw new AssertionError("Unexpected configuration warning: " + message);
		}
	}

	private static final class RecordingTriggerRegistry
			implements SokletApplicationTriggerRegistry {
		@NonNull
		private final CountDownLatch registered = new CountDownLatch(1);
		@NonNull
		private final AtomicReference<Runnable> shutdownIntent =
				new AtomicReference<>();

		@Override
		@NonNull
		public SokletApplicationTriggerRegistration register(
				@NonNull Runnable shutdownIntent) {
			if (!this.shutdownIntent.compareAndSet(null,
					requireNonNull(shutdownIntent)))
				throw new AssertionError("Shutdown trigger was registered twice");
			this.registered.countDown();
			return () -> this.shutdownIntent.compareAndSet(shutdownIntent, null);
		}

		void trigger() throws InterruptedException {
			Assertions.assertTrue(this.registered.await(5, TimeUnit.SECONDS),
					"Shutdown trigger was not registered");
			requireNonNull(this.shutdownIntent.get()).run();
		}

		void triggerIfRegistered() {
			Runnable intent = this.shutdownIntent.get();
			if (intent != null)
				intent.run();
		}
	}

	private static final class RecordingReporter
			implements LifecycleTerminalReporter {
		@NonNull
		private final AtomicReference<SokletApplicationTerminalSnapshot> snapshot =
				new AtomicReference<>();
		@NonNull
		private final CountDownLatch reported = new CountDownLatch(1);

		@Override
		public void report(@NonNull SokletApplicationTerminalSnapshot snapshot) {
			this.snapshot.compareAndSet(null, requireNonNull(snapshot));
			this.reported.countDown();
		}

		@NonNull
		SokletApplicationTerminalSnapshot awaitSnapshot()
				throws InterruptedException {
			Assertions.assertTrue(this.reported.await(5, TimeUnit.SECONDS),
					"Terminal reporter was not invoked");
			return requireNonNull(this.snapshot.get());
		}
	}

	private static final class ObservedLauncher
			implements LifecycleWorkers.Launcher {
		@NonNull
		private final ConcurrentMap<String, CountDownLatch> completions =
				new ConcurrentHashMap<>();
		@NonNull
		private final Set<Thread> threads = ConcurrentHashMap.newKeySet();
		@NonNull
		private final AtomicReference<Throwable> workerFailure =
				new AtomicReference<>();

		@Override
		public void launch(@NonNull String name, @NonNull Runnable runnable) {
			CountDownLatch completion = this.completions.computeIfAbsent(name,
					ignored -> new CountDownLatch(1));
			Thread thread = new Thread(() -> {
				try {
					runnable.run();
				} catch (Throwable failure) {
					this.workerFailure.compareAndSet(null, failure);
				} finally {
					completion.countDown();
				}
			}, name);
			thread.setDaemon(true);
			this.threads.add(thread);
			thread.start();
		}

		boolean awaitCompleted(@NonNull String name) throws InterruptedException {
			return this.completions.computeIfAbsent(requireNonNull(name),
					ignored -> new CountDownLatch(1)).await(5, TimeUnit.SECONDS);
		}

		void awaitTermination() throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			for (Thread thread : List.copyOf(this.threads)) {
				long remaining = deadline - System.nanoTime();
				if (remaining > 0L)
					thread.join(Math.max(1L,
							TimeUnit.NANOSECONDS.toMillis(remaining)));
			}
			long liveThreads = this.threads.stream().filter(Thread::isAlive).count();
			Assertions.assertEquals(0L, liveThreads,
					"Diagnostics lifecycle workers did not terminate");
			Assertions.assertNull(this.workerFailure.get(),
					"A diagnostics lifecycle worker failed");
		}
	}

	private static final class RunnerCall {
		@NonNull
		private final AtomicReference<InternalShutdownResult> result =
				new AtomicReference<>();
		@NonNull
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		@NonNull
		private final CountDownLatch done = new CountDownLatch(1);
		void await() throws InterruptedException {
			Assertions.assertTrue(this.done.await(10, TimeUnit.SECONDS),
					"Standalone diagnostics runner did not finish");
		}

		@Nullable
		InternalShutdownResult result() {
			return this.result.get();
		}

		@Nullable
		Throwable failure() {
			return this.failure.get();
		}
	}

	public static final class DiagnosticsResource {
		@GET("/diagnostics")
		@NonNull
		public String get() {
			return "diagnostics";
		}
	}
}
