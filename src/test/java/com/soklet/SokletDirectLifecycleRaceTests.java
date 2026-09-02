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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Regression coverage for direct-owner race linearization and containment. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class SokletDirectLifecycleRaceTests {
	@NonNull
	private static final Duration SHORT_PHASE = Duration.ofMillis(80);
	@NonNull
	private static final Duration LONG_STARTUP = Duration.ofSeconds(5);
	@NonNull
	private final Set<ExecutorService> executors =
			java.util.concurrent.ConcurrentHashMap.newKeySet();

	@AfterEach
	void tearDown() {
		for (ExecutorService executor : this.executors)
			executor.shutdownNow();
	}

	@Test
	void closeAfterStartClaimCannotPublishNotAttempted() throws Exception {
		CountDownLatch setupEntered = new CountDownLatch(1);
		CountDownLatch releaseSetup = new CountDownLatch(1);
		CountDownLatch setupReturned = new CountDownLatch(1);
		BlockingResolver resolver = new BlockingResolver(completeResolver(),
				setupEntered, releaseSetup, setupReturned);
		RaceHttpEndpoint http = new RaceHttpEndpoint();
		Soklet soklet = Soklet.fromConfig(config(http, resolver)
				.internalLifecyclePolicy(shortCancellationPolicy()).build());
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() -> captureFailure(soklet::start));

		Assertions.assertTrue(setupEntered.await(2, TimeUnit.SECONDS));
		Assertions.assertEquals(InternalLifecycleStateMachine.State.STARTING,
				soklet.getDirectLifecycle().state());
		Future<Throwable> close = executor.submit(() -> captureFailure(soklet::close));

		Throwable startFailure;
		try {
			startFailure = start.get(3, TimeUnit.SECONDS);
			Assertions.assertInstanceOf(ShutdownIncompleteException.class,
					close.get(3, TimeUnit.SECONDS));
		} finally {
			releaseSetup.countDown();
		}

		Assertions.assertInstanceOf(SokletStartupException.class, startFailure);
		InternalShutdownResult result = soklet.getDirectLifecycle().result()
				.orElseThrow();
		Assertions.assertNotEquals(InternalStartupDisposition.NOT_ATTEMPTED,
				result.startupDisposition(),
				"A shutdown after the start claim is cancellation, never close-before-start");
		Assertions.assertEquals(InternalStartupDisposition.CANCELED,
				result.startupDisposition());
		Assertions.assertEquals(0, http.attachCalls());
		Assertions.assertTrue(setupReturned.await(2, TimeUnit.SECONDS));
	}

	@Test
	void lateBlockedAttachReturnIsInertAndCannotEscapeTerminalEvidence()
			throws Exception {
		CountDownLatch attachEntered = new CountDownLatch(1);
		CountDownLatch releaseAttach = new CountDownLatch(1);
		CountDownLatch attachReturned = new CountDownLatch(1);
		RaceHttpEndpoint http = new RaceHttpEndpoint();
		http.onAttach(() -> {
			attachEntered.countDown();
			awaitIgnoringInterrupts(releaseAttach);
			attachReturned.countDown();
		});
		Soklet soklet = Soklet.fromConfig(config(http, completeResolver())
				.internalLifecyclePolicy(shortCancellationPolicy()).build());
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() -> captureFailure(soklet::start));

		Assertions.assertTrue(attachEntered.await(2, TimeUnit.SECONDS));
		Future<Throwable> close = executor.submit(() -> captureFailure(soklet::close));
		Throwable startFailure;
		InternalShutdownResult terminal;
		try {
			startFailure = start.get(3, TimeUnit.SECONDS);
			Assertions.assertInstanceOf(ShutdownIncompleteException.class,
					close.get(3, TimeUnit.SECONDS));
			terminal = soklet.getDirectLifecycle().result().orElseThrow();
		} finally {
			releaseAttach.countDown();
		}

		Assertions.assertInstanceOf(SokletStartupException.class, startFailure);
		Assertions.assertEquals(InternalStartupDisposition.CANCELED,
				terminal.startupDisposition());
		InternalLifecycleComponentShutdownResult httpResult = terminal
				.participantResult(InternalLifecycleComponentType.HTTP).orElseThrow();
		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN,
				httpResult.disposition(),
				"A still-running attachment call has exact unknown termination");
		Assertions.assertFalse(terminal.isComplete());
		Assertions.assertEquals(0, http.startCalls());

		Assertions.assertTrue(attachReturned.await(2, TimeUnit.SECONDS));
		Assertions.assertTrue(http.invoke("/race").isEmpty(),
				"A handler returned by an abandoned attachment must remain inadmissible");
		Assertions.assertEquals(SokletStatus.CLOSED, soklet.getStatus());
		Assertions.assertSame(terminal,
				soklet.getDirectLifecycle().result().orElseThrow(),
				"Late attachment completion cannot replace immutable terminal evidence");
	}

	@Test
	void installedAttachmentWithActiveWrapperRetainsTransportResidualEvidence()
			throws Exception {
		CountDownLatch attachmentSettled = new CountDownLatch(1);
		CountDownLatch releaseAttachmentWrapper = new CountDownLatch(1);
		RaceHttpEndpoint http = new RaceHttpEndpoint();
		SokletConfig ownerConfig = config(http, completeResolver())
				.internalLifecyclePolicy(shortCancellationPolicy()).build();
		Soklet callbackSoklet = Soklet.fromConfig(config(new RaceHttpEndpoint(),
				completeResolver()).build());
		LifecycleWorkers workers = new LifecycleWorkers();
		SokletDirectLifecycle owner = new SokletDirectLifecycle(callbackSoklet,
				ownerConfig, new SokletFrameworkSetup(ownerConfig), NanoClock.system(),
				workers, () -> { }, () -> { }, () -> { }, () -> {
					attachmentSettled.countDown();
					awaitIgnoringInterrupts(releaseAttachmentWrapper);
				});
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() -> captureFailure(owner::start));

		try {
			Assertions.assertTrue(attachmentSettled.await(2, TimeUnit.SECONDS),
					"Attachment did not settle before its wrapper was paused");
			Future<Throwable> close = executor.submit(() ->
					captureFailure(() -> joinShutdown(owner)));

			Throwable startFailure = start.get(3, TimeUnit.SECONDS);
			Throwable closeFailure = close.get(3, TimeUnit.SECONDS);
			SokletStartupException startup = Assertions.assertInstanceOf(
					SokletStartupException.class, startFailure);
			ShutdownIncompleteException incomplete = Assertions.assertInstanceOf(
					ShutdownIncompleteException.class, closeFailure);
			InternalShutdownResult result = startup.getInternalShutdownResult();

			Assertions.assertSame(result, incomplete.getInternalShutdownResult());
			Assertions.assertSame(result, owner.result().orElseThrow());
			Assertions.assertEquals(InternalStartupDisposition.CANCELED,
					result.startupDisposition());
			Assertions.assertEquals(result.participantResults().size(),
					result.participantResults().stream()
							.map(InternalLifecycleComponentShutdownResult::kind)
							.distinct().count(),
					"Synthetic attachment evidence cannot duplicate a configured kind");

			InternalLifecycleComponentShutdownResult httpResult = result
					.participantResult(InternalLifecycleComponentType.HTTP).orElseThrow();
			Assertions.assertEquals(
					InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN,
					httpResult.disposition(),
					"The configured transport owns its still-active attach wrapper");
			Assertions.assertTrue(httpResult.residualActivity().contains(
					InternalResidualActivityType.LIFECYCLE_CALL));
			Assertions.assertTrue(result.participantResult(
					InternalLifecycleComponentType.FRAMEWORK).isEmpty(),
					"Attachment wrapper evidence must project onto its transport row");
			Assertions.assertFalse(result.isComplete());
		} finally {
			releaseAttachmentWrapper.countDown();
			try {
				awaitCondition(() -> workers.active(
						LifecycleWorkers.Role.LIFECYCLE_CALL) == 0,
						"Attachment lifecycle worker survived bounded cleanup");
			} finally {
				owner.shutdown();
				owner.awaitCompletion();
				callbackSoklet.close();
			}
		}
	}

	@Test
	void resolverCancellationSentinelDoesNotBecomeStartupOrResultFailure()
			throws Exception {
		CountDownLatch loaderEntered = new CountDownLatch(1);
		CountDownLatch releaseLoader = new CountDownLatch(1);
		AtomicReference<Throwable> resolverOwnerFailure = new AtomicReference<>();
		DefaultResourceMethodResolver lazy =
				DefaultResourceMethodResolver.lazyClasspathResolverForTesting(
						classLoader -> {
							loaderEntered.countDown();
							awaitIgnoringInterrupts(releaseLoader);
							return DefaultResourceMethodResolver.fromClasses(
									Set.of(RaceResource.class));
						});
		Thread resolverOwner = new Thread(() -> resolverOwnerFailure.set(
				captureFailure(lazy::getResourceMethods)),
				"direct-race-resolver-owner");
		resolverOwner.setDaemon(true);
		resolverOwner.start();
		Assertions.assertTrue(loaderEntered.await(2, TimeUnit.SECONDS));

		RaceHttpEndpoint http = new RaceHttpEndpoint();
		Soklet soklet = Soklet.fromConfig(config(http, lazy)
				.internalLifecyclePolicy(shortCancellationPolicy()).build());
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() -> captureFailure(soklet::start));
		Throwable startFailure;
		try {
			awaitCondition(SokletDirectLifecycleRaceTests::resolverLifecycleWaitIsBlocked,
					"The direct setup call did not enter the shared resolver wait");
			Future<?> close = executor.submit(soklet::close);
			startFailure = start.get(3, TimeUnit.SECONDS);
			close.get(3, TimeUnit.SECONDS);
		} finally {
			releaseLoader.countDown();
			resolverOwner.join(TimeUnit.SECONDS.toMillis(3));
		}

		Assertions.assertFalse(resolverOwner.isAlive());
		Assertions.assertNull(resolverOwnerFailure.get());
		Assertions.assertInstanceOf(SokletStartupException.class, startFailure);
		Assertions.assertFalse(throwableGraphContains(startFailure,
				DefaultResourceMethodResolver.StartupWaitCanceledException.class),
				"The resolver's private wait sentinel must be normalized at the owner boundary");
		InternalShutdownResult result = soklet.getDirectLifecycle().result()
				.orElseThrow();
		Assertions.assertEquals(InternalStartupDisposition.CANCELED,
				result.startupDisposition());
		Assertions.assertTrue(result.participantResults().stream()
				.flatMap(participant -> participant.failures().stream())
				.noneMatch(failure -> throwableGraphContains(failure,
						DefaultResourceMethodResolver.StartupWaitCanceledException.class)),
				"Private resolver cancellation cannot leak into retained shutdown evidence");
		Assertions.assertEquals(0, http.attachCalls());
	}

	@Test
	void sharedLazyResolverDeadlineRemainsTimedOutNotCallFailure()
			throws Exception {
		CountDownLatch loaderEntered = new CountDownLatch(1);
		CountDownLatch releaseLoader = new CountDownLatch(1);
		CountDownLatch coordinatorWaitEntered = new CountDownLatch(1);
		AtomicReference<Throwable> resolverOwnerFailure = new AtomicReference<>();
		DefaultResourceMethodResolver lazy =
				DefaultResourceMethodResolver.lazyClasspathResolverForTesting(
						classLoader -> {
							loaderEntered.countDown();
							awaitIgnoringInterrupts(releaseLoader);
							return DefaultResourceMethodResolver.fromClasses(
									Set.of(RaceResource.class));
						});
		Thread resolverOwner = new Thread(() -> resolverOwnerFailure.set(
				captureFailure(lazy::getResourceMethods)),
				"direct-race-deadline-resolver-owner");
		resolverOwner.setDaemon(true);
		resolverOwner.start();
		Assertions.assertTrue(loaderEntered.await(2, TimeUnit.SECONDS));

		long startupDeadline = LONG_STARTUP.toNanos();
		AtomicInteger coordinatorClockReads = new AtomicInteger();
		NanoClock clock = () -> {
			String threadName = Thread.currentThread().getName();
			if (threadName.equals("soklet-lifecycle-coordinator")) {
				int read = coordinatorClockReads.getAndIncrement();
				if (read == 1) {
					coordinatorWaitEntered.countDown();
					return 0L;
				}
				return read == 0 ? 0L : startupDeadline;
			}
			if (threadName.equals("soklet-framework-setup"))
				return startupDeadline;
			return coordinatorWaitEntered.getCount() == 0
					? startupDeadline : 0L;
		};
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> {
			Thread worker = new Thread(() -> {
				if (name.equals("soklet-framework-setup"))
					awaitIgnoringInterrupts(coordinatorWaitEntered);
				task.run();
			}, name);
			worker.setDaemon(true);
			worker.start();
		});
		StartupFailureObserver observer = new StartupFailureObserver();
		RaceHttpEndpoint http = new RaceHttpEndpoint();
		SokletConfig ownerConfig = config(http, lazy)
				.lifecycleObserver(observer)
				.internalLifecyclePolicy(shortCancellationPolicy()).build();
		Soklet callbackSoklet = Soklet.fromConfig(config(new RaceHttpEndpoint(),
				completeResolver()).build());
		SokletDirectLifecycle owner = new SokletDirectLifecycle(callbackSoklet,
				ownerConfig, new SokletFrameworkSetup(ownerConfig), clock, workers);
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() -> captureFailure(owner::start));

		try {
			SokletStartupException startup = Assertions.assertInstanceOf(
					SokletStartupException.class,
					start.get(3, TimeUnit.SECONDS));
			Assertions.assertEquals(InternalStartupDisposition.TIMED_OUT,
					startup.getInternalStartupDisposition());
			TimeoutException exactTimeout = Assertions.assertInstanceOf(
					TimeoutException.class, startup.getCause());
			Assertions.assertEquals("Soklet startup deadline was reached",
					exactTimeout.getMessage());
			Assertions.assertTrue(observer.awaitFailure());
			Assertions.assertSame(exactTimeout, observer.failure());
			InternalShutdownResult result = startup.getInternalShutdownResult();
			Assertions.assertSame(result, owner.result().orElseThrow());
			Assertions.assertEquals(InternalStartupDisposition.TIMED_OUT,
					result.startupDisposition());
			Assertions.assertFalse(throwableGraphContains(startup,
					DefaultResourceMethodResolver.StartupWaitCanceledException.class),
					"The private resolver sentinel must remain outcome-neutral");
			Assertions.assertEquals(0, http.attachCalls());
		} finally {
			releaseLoader.countDown();
			resolverOwner.join(TimeUnit.SECONDS.toMillis(3));
			owner.shutdown();
			owner.awaitCompletion();
			callbackSoklet.close();
		}

		Assertions.assertFalse(resolverOwner.isAlive());
		Assertions.assertNull(resolverOwnerFailure.get());
	}

	@Test
	void transitionWorkerLaunchFailureCannotStrandReadyOrTerminalPublication()
			throws Exception {
		RuntimeException transitionLaunchFailure = new RuntimeException(
				"synthetic transition launcher failure");
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> {
			if (name.equals("soklet-lifecycle-observer"))
				throw transitionLaunchFailure;
			Thread worker = new Thread(task, "race-" + name);
			worker.setDaemon(true);
			worker.start();
		});
		SokletConfig ownerConfig = SokletConfig
				.withHttpServer(new RaceHttpEndpoint())
				.resourceMethodResolver(completeResolver())
				.lifecycleObserver(new LifecycleObserver() { })
				.internalLifecyclePolicy(shortCancellationPolicy())
				.build();
		Soklet callbackSoklet = Soklet.fromConfig(config(new RaceHttpEndpoint(),
				completeResolver()).build());
		SokletDirectLifecycle owner = new SokletDirectLifecycle(callbackSoklet,
				ownerConfig, new SokletFrameworkSetup(ownerConfig),
				NanoClock.system(), workers);

		try {
			Throwable startFailure = captureFailure(owner::start);
			if (startFailure != null)
				Assertions.fail("Observer infrastructure is non-controlling",
						startFailure);
			Assertions.assertEquals(InternalLifecycleStateMachine.State.READY,
					owner.state());

			joinShutdown(owner);

			InternalShutdownResult result = owner.result().orElseThrow();
			Assertions.assertEquals(InternalStartupDisposition.READY,
					result.startupDisposition());
			Assertions.assertEquals(InternalLifecycleStateMachine.State.CLOSED,
					owner.state());
			Assertions.assertSame(result, owner.awaitCompletion());
		} finally {
			callbackSoklet.close();
		}
	}

	@Test
	void shutdownAfterReadyLinearizationCannotRetroactivelyCancelStartup()
			throws Exception {
		CountDownLatch readyLinearized = new CountDownLatch(1);
		CountDownLatch releaseReadyPublication = new CountDownLatch(1);
		RaceHttpEndpoint http = new RaceHttpEndpoint();
		SokletConfig ownerConfig = config(http, completeResolver())
				.internalLifecyclePolicy(shortCancellationPolicy()).build();
		Soklet callbackSoklet = Soklet.fromConfig(config(new RaceHttpEndpoint(),
				completeResolver()).build());
		SokletDirectLifecycle owner = new SokletDirectLifecycle(callbackSoklet,
				ownerConfig, new SokletFrameworkSetup(ownerConfig), NanoClock.system(),
				new LifecycleWorkers(), () -> {
					readyLinearized.countDown();
					awaitIgnoringInterrupts(releaseReadyPublication);
				});
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() -> captureFailure(owner::start));

		try {
			Assertions.assertTrue(readyLinearized.await(2, TimeUnit.SECONDS));
			Assertions.assertEquals(InternalLifecycleStateMachine.State.READY,
					owner.state());
			Assertions.assertFalse(owner.isStarted(),
					"The test must stop between the READY CAS and readiness publication");
			owner.requestShutdownIntent();
			releaseReadyPublication.countDown();

			Throwable startFailure = start.get(3, TimeUnit.SECONDS);
			if (startFailure != null)
				Assertions.fail("Shutdown after the READY CAS cannot rewrite startup "
						+ "as cancellation", startFailure);
			joinShutdown(owner);
			Assertions.assertEquals(InternalStartupDisposition.READY,
					owner.result().orElseThrow().startupDisposition());
		} finally {
			releaseReadyPublication.countDown();
			callbackSoklet.close();
		}
	}

	@Test
	void interruptResponsiveActiveStartTimeoutRemainsTimedOutNotUnexpected()
			throws Exception {
		InterruptResponsiveStartGate startGate =
				new InterruptResponsiveStartGate();
		StartupFailureObserver observer = new StartupFailureObserver();
		RaceHttpEndpoint http = new RaceHttpEndpoint();
		http.onStart(startGate::block);
		Soklet soklet = Soklet.fromConfig(config(http, completeResolver())
				.lifecycleObserver(observer)
				.internalLifecyclePolicy(shortStartupTimeoutPolicy()).build());
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() -> captureFailure(soklet::start));

		Assertions.assertTrue(startGate.awaitEntered());
		Throwable startFailure;
		try {
			startFailure = start.get(3, TimeUnit.SECONDS);
		} finally {
			startGate.release();
		}

		Assertions.assertTrue(startGate.awaitInterrupted(),
				"The startup deadline must interrupt the active start call");
		Assertions.assertInstanceOf(SokletStartupException.class, startFailure);
		SokletStartupException startupException =
				(SokletStartupException) startFailure;
		Assertions.assertEquals(InternalStartupDisposition.TIMED_OUT,
				startupException.getInternalStartupDisposition());
		Throwable exactTimeout = startupException.getCause();
		Assertions.assertEquals(TimeoutException.class, exactTimeout.getClass(),
				"Timeout must remain the exact controlling cause, not a wrapper");
		Assertions.assertNull(exactTimeout.getCause());
		Assertions.assertTrue(observer.awaitFailure());
		Assertions.assertSame(exactTimeout, observer.failure(),
				"Observation must receive the exact timeout elected by the owner");

		InternalShutdownResult result = startupException
				.getInternalShutdownResult();
		Assertions.assertSame(result,
				soklet.getDirectLifecycle().result().orElseThrow());
		Assertions.assertEquals(InternalStartupDisposition.TIMED_OUT,
				result.startupDisposition());
		Assertions.assertNotEquals(
				InternalLifecycleComponentShutdownDisposition.UNEXPECTED_TERMINATION,
				result.participantResult(InternalLifecycleComponentType.HTTP)
						.orElseThrow().disposition());
		Assertions.assertDoesNotThrow(soklet::close,
				"Deadline cancellation must not publish unexpected termination");
	}

	@Test
	void externalCloseOfInterruptResponsiveActiveStartRemainsCancelled()
			throws Exception {
		InterruptResponsiveStartGate startGate =
				new InterruptResponsiveStartGate();
		StartupFailureObserver observer = new StartupFailureObserver();
		RaceHttpEndpoint http = new RaceHttpEndpoint();
		http.onStart(startGate::block);
		Soklet soklet = Soklet.fromConfig(config(http, completeResolver())
				.lifecycleObserver(observer)
				.internalLifecyclePolicy(shortCancellationPolicy()).build());
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() -> captureFailure(soklet::start));

		Assertions.assertTrue(startGate.awaitEntered());
		Future<Throwable> close = executor.submit(() ->
				captureFailure(soklet::close));
		Throwable startFailure;
		Throwable closeFailure;
		try {
			startFailure = start.get(3, TimeUnit.SECONDS);
			closeFailure = close.get(3, TimeUnit.SECONDS);
		} finally {
			startGate.release();
		}

		Assertions.assertTrue(startGate.awaitInterrupted(),
				"External close must interrupt the active start call");
		Assertions.assertNull(closeFailure,
				"External close should join the clean cancellation result");
		Assertions.assertInstanceOf(SokletStartupException.class, startFailure);
		SokletStartupException startupException =
				(SokletStartupException) startFailure;
		Assertions.assertEquals(InternalStartupDisposition.CANCELED,
				startupException.getInternalStartupDisposition());
		Throwable exactCancellation = startupException.getCause();
		Assertions.assertEquals(IllegalStateException.class,
				exactCancellation.getClass(),
				"External cancellation must expose its exact cause, not a wrapper");
		Assertions.assertEquals(
				"Soklet shutdown was requested during startup",
				exactCancellation.getMessage());
		Assertions.assertNull(exactCancellation.getCause());
		Assertions.assertTrue(observer.awaitFailure());
		Assertions.assertSame(exactCancellation, observer.failure(),
				"Observation must receive the exact cancellation cause");

		InternalShutdownResult result = startupException
				.getInternalShutdownResult();
		Assertions.assertSame(result,
				soklet.getDirectLifecycle().result().orElseThrow());
		Assertions.assertEquals(InternalStartupDisposition.CANCELED,
				result.startupDisposition());
		Assertions.assertNotEquals(
				InternalLifecycleComponentShutdownDisposition.UNEXPECTED_TERMINATION,
				result.participantResult(InternalLifecycleComponentType.HTTP)
						.orElseThrow().disposition());
		Assertions.assertDoesNotThrow(soklet::close,
				"External cancellation must not publish unexpected termination");
	}

	@Test
	void externalShutdownWinsBeforeInducedStartupCallFailure() throws Exception {
		CountDownLatch startEntered = new CountDownLatch(1);
		CountDownLatch releaseStart = new CountDownLatch(1);
		CountDownLatch inducedFailureThrown = new CountDownLatch(1);
		CountDownLatch selectionEntered = new CountDownLatch(1);
		CountDownLatch releaseSelection = new CountDownLatch(1);
		UncheckedIOException inducedFailure = new UncheckedIOException(
				"induced after owner cancellation", new IOException("owner stopped"));
		StartupFailureObserver observer = new StartupFailureObserver();
		RaceHttpEndpoint http = new RaceHttpEndpoint();
		http.onStart(() -> {
			startEntered.countDown();
			awaitIgnoringInterrupts(releaseStart);
			inducedFailureThrown.countDown();
			throw inducedFailure;
		});
		SokletConfig ownerConfig = config(http, completeResolver())
				.lifecycleObserver(observer)
				.internalLifecyclePolicy(shortCancellationPolicy()).build();
		Soklet callbackSoklet = Soklet.fromConfig(config(new RaceHttpEndpoint(),
				completeResolver()).build());
		LifecycleWorkers workers = new LifecycleWorkers();
		SokletDirectLifecycle owner = new SokletDirectLifecycle(callbackSoklet,
				ownerConfig, new SokletFrameworkSetup(ownerConfig), NanoClock.system(),
				workers, () -> { }, () -> { }, () -> { }, () -> { }, name -> {
					if (!name.equals("soklet-start-http"))
						return;
					selectionEntered.countDown();
					awaitIgnoringInterrupts(releaseSelection);
				});
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() -> captureFailure(owner::start));

		try {
			Assertions.assertTrue(startEntered.await(2, TimeUnit.SECONDS));
			CompletionStage<ShutdownResult> shutdown = owner.shutdown();
			Assertions.assertTrue(selectionEntered.await(2, TimeUnit.SECONDS));
			releaseStart.countDown();
			Assertions.assertTrue(inducedFailureThrown.await(2, TimeUnit.SECONDS));
			awaitCondition(() -> workers.active(
					LifecycleWorkers.Role.LIFECYCLE_CALL) == 0,
					"The induced startup failure did not finish before selection");
			releaseSelection.countDown();

			SokletStartupException startup = Assertions.assertInstanceOf(
					SokletStartupException.class,
					start.get(3, TimeUnit.SECONDS));
			InternalShutdownResult result = startup.getInternalShutdownResult();
			Assertions.assertSame(result, shutdown.toCompletableFuture().get(
					3, TimeUnit.SECONDS).internalResult());
			Assertions.assertEquals(InternalStartupDisposition.CANCELED,
					result.startupDisposition());
			IllegalStateException exactCancellation = Assertions.assertInstanceOf(
					IllegalStateException.class, startup.getCause());
			Assertions.assertEquals("Soklet shutdown was requested during startup",
					exactCancellation.getMessage());
			Assertions.assertTrue(observer.awaitFailure());
			Assertions.assertSame(exactCancellation, observer.failure());
			Assertions.assertNotSame(inducedFailure, startup.getCause(),
					"A post-cancellation transport failure cannot replace the owner winner");
			Assertions.assertSame(result, owner.result().orElseThrow());
			Assertions.assertTrue(result.participantResult(
					InternalLifecycleComponentType.HTTP).orElseThrow().failures().stream()
					.anyMatch(failure -> failure == inducedFailure),
					"The losing induced failure must remain participant evidence");
		} finally {
			releaseStart.countDown();
			releaseSelection.countDown();
			owner.shutdown();
			owner.awaitCompletion();
			callbackSoklet.close();
		}
	}

	@Test
	void startupCallFailureWinsBeforeLaterExternalShutdown() throws Exception {
		CountDownLatch startEntered = new CountDownLatch(1);
		CountDownLatch releaseStart = new CountDownLatch(1);
		CountDownLatch selectionEntered = new CountDownLatch(1);
		CountDownLatch releaseSelection = new CountDownLatch(1);
		UncheckedIOException exactFailure = new UncheckedIOException(
				"genuine startup-call failure", new IOException("startup failed"));
		StartupFailureObserver observer = new StartupFailureObserver();
		RaceHttpEndpoint http = new RaceHttpEndpoint();
		http.onStart(() -> {
			startEntered.countDown();
			awaitIgnoringInterrupts(releaseStart);
			throw exactFailure;
		});
		SokletConfig ownerConfig = config(http, completeResolver())
				.lifecycleObserver(observer)
				.internalLifecyclePolicy(shortCancellationPolicy()).build();
		Soklet callbackSoklet = Soklet.fromConfig(config(new RaceHttpEndpoint(),
				completeResolver()).build());
		SokletDirectLifecycle owner = new SokletDirectLifecycle(callbackSoklet,
				ownerConfig, new SokletFrameworkSetup(ownerConfig), NanoClock.system(),
				new LifecycleWorkers(), () -> { }, () -> { }, () -> { }, () -> { },
				name -> {
					if (!name.equals("soklet-start-http"))
						return;
					selectionEntered.countDown();
					awaitIgnoringInterrupts(releaseSelection);
				});
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() -> captureFailure(owner::start));

		try {
			Assertions.assertTrue(startEntered.await(2, TimeUnit.SECONDS));
			releaseStart.countDown();
			Assertions.assertTrue(selectionEntered.await(2, TimeUnit.SECONDS),
					"The coordinator did not reach startup outcome selection");
			CompletionStage<ShutdownResult> laterShutdown = owner.shutdown();
			releaseSelection.countDown();

			SokletStartupException startup = Assertions.assertInstanceOf(
					SokletStartupException.class,
					start.get(3, TimeUnit.SECONDS));
			InternalShutdownResult result = startup.getInternalShutdownResult();
			Assertions.assertSame(result, laterShutdown.toCompletableFuture().get(
					3, TimeUnit.SECONDS).internalResult());
			Assertions.assertEquals(InternalStartupDisposition.FAILED,
					result.startupDisposition());
			Assertions.assertSame(exactFailure, startup.getCause(),
					"A later owner stop cannot replace the elected startup-call failure");
			Assertions.assertTrue(observer.awaitFailure());
			Assertions.assertSame(exactFailure, observer.failure());
			Assertions.assertTrue(result.participantResult(
					InternalLifecycleComponentType.HTTP).orElseThrow().failures().stream()
					.anyMatch(failure -> failure == exactFailure));
		} finally {
			releaseStart.countDown();
			releaseSelection.countDown();
			owner.shutdown();
			owner.awaitCompletion();
			callbackSoklet.close();
		}
	}

	@Test
	void startupCallFailureWinsBeforeLaterPeerTermination() throws Exception {
		CountDownLatch selectionEntered = new CountDownLatch(1);
		CountDownLatch releaseSelection = new CountDownLatch(1);
		UncheckedIOException exactFailure = new UncheckedIOException(
				"genuine SSE startup failure", new IOException("SSE startup failed"));
		AssertionError laterPeerFailure = new AssertionError(
				"HTTP terminated after the startup failure");
		StartupFailureObserver observer = new StartupFailureObserver();
		RaceHttpEndpoint http = new RaceHttpEndpoint();
		RaceSseEndpoint sse = new RaceSseEndpoint();
		sse.onStart(() -> {
			throw exactFailure;
		});
		SokletConfig ownerConfig = config(http, completeResolver())
				.sseServer(sse)
				.lifecycleObserver(observer)
				.internalLifecyclePolicy(shortCancellationPolicy()).build();
		Soklet callbackSoklet = Soklet.fromConfig(config(new RaceHttpEndpoint(),
				completeResolver()).build());
		SokletDirectLifecycle owner = new SokletDirectLifecycle(callbackSoklet,
				ownerConfig, new SokletFrameworkSetup(ownerConfig), NanoClock.system(),
				new LifecycleWorkers(), () -> { }, () -> { }, () -> { }, () -> { },
				name -> {
					if (!name.equals("soklet-start-sse"))
						return;
					selectionEntered.countDown();
					awaitIgnoringInterrupts(releaseSelection);
				});
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() -> captureFailure(owner::start));

		try {
			Assertions.assertTrue(selectionEntered.await(2, TimeUnit.SECONDS),
					"The coordinator did not pause after the SSE failure election");
			http.signalFailure(laterPeerFailure);
			releaseSelection.countDown();

			SokletStartupException startup = Assertions.assertInstanceOf(
					SokletStartupException.class,
					start.get(3, TimeUnit.SECONDS));
			InternalShutdownResult result = startup.getInternalShutdownResult();
			Assertions.assertEquals(InternalStartupDisposition.FAILED,
					result.startupDisposition());
			Assertions.assertSame(exactFailure, startup.getCause(),
					"A later peer termination cannot replace the elected call failure");
			Assertions.assertTrue(observer.awaitFailure());
			Assertions.assertSame(exactFailure, observer.failure());
			Assertions.assertTrue(result.participantResult(
					InternalLifecycleComponentType.SSE).orElseThrow().failures().stream()
					.anyMatch(failure -> failure == exactFailure));
			Assertions.assertTrue(result.participantResult(
					InternalLifecycleComponentType.HTTP).orElseThrow().failures().stream()
					.anyMatch(failure -> failure == laterPeerFailure),
					"The losing peer failure must remain participant evidence");
		} finally {
			releaseSelection.countDown();
			owner.shutdown();
			owner.awaitCompletion();
			callbackSoklet.close();
		}
	}

	@Test
	void earlierParticipantFailureBoundsBlockedLaterStartAndKeepsExactCause()
			throws Exception {
		RuntimeException exactFailure = new RuntimeException(
				"earlier HTTP participant failed");
		RaceHttpEndpoint http = new RaceHttpEndpoint();
		RaceSseEndpoint sse = new RaceSseEndpoint();
		CountDownLatch laterStartEntered = new CountDownLatch(1);
		CountDownLatch releaseLaterStart = new CountDownLatch(1);
		CountDownLatch laterStartReturned = new CountDownLatch(1);
		sse.onStart(() -> {
			laterStartEntered.countDown();
			awaitIgnoringInterrupts(releaseLaterStart);
			laterStartReturned.countDown();
		});
		InternalLifecyclePolicy policy = new InternalLifecyclePolicy(
				Optional.of(LONG_STARTUP), SHORT_PHASE, SHORT_PHASE, SHORT_PHASE);
		Soklet soklet = Soklet.fromConfig(config(http, completeResolver())
				.sseServer(sse).internalLifecyclePolicy(policy).build());
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() -> captureFailure(soklet::start));

		Assertions.assertTrue(laterStartEntered.await(2, TimeUnit.SECONDS));
		http.signalFailure(exactFailure);
		Throwable startFailure = null;
		boolean bounded = true;
		try {
			startFailure = start.get(2, TimeUnit.SECONDS);
		} catch (TimeoutException timeout) {
			bounded = false;
		} finally {
			releaseLaterStart.countDown();
		}
		if (!bounded) {
			Throwable eventual = start.get(3, TimeUnit.SECONDS);
			Assertions.fail("An earlier committed participant failure did not "
					+ "cancel the later blocked start within the shared budget",
					eventual);
		}

		Assertions.assertInstanceOf(SokletStartupException.class, startFailure);
		SokletStartupException startupException =
				(SokletStartupException) startFailure;
		Assertions.assertEquals(InternalStartupDisposition.FAILED,
				startupException.getInternalStartupDisposition());
		Assertions.assertSame(exactFailure, startupException.getCause(),
				"The first participant failure must remain the exact startup cause");
		InternalShutdownResult result = startupException
				.getInternalShutdownResult();
		Assertions.assertTrue(result.participantResult(InternalLifecycleComponentType.HTTP)
				.orElseThrow().failures().stream()
				.anyMatch(failure -> failure == exactFailure));
		Assertions.assertTrue(laterStartReturned.await(2, TimeUnit.SECONDS));
		Assertions.assertEquals(SokletStatus.CLOSED, soklet.getStatus());
	}

	@Test
	void reachableTransportKeepsOwnershipAfterOriginalSokletIsCollected()
			throws Exception {
		RaceHttpEndpoint reachableTransport = new RaceHttpEndpoint();
		WeakReference<Soklet> originalOwner = abandonOwner(reachableTransport);

		awaitCollected(originalOwner);
		Assertions.assertNull(originalOwner.get(),
				"The test must prove the original weak owner is gone");

		TransportOwnershipException conflict = Assertions.assertThrows(
				TransportOwnershipException.class, () -> Soklet.fromConfig(
						config(reachableTransport, completeResolver()).build()));
		Assertions.assertEquals(ShutdownComponentType.HTTP,
				conflict.getShutdownComponentType());
		Assertions.assertSame(RaceHttpEndpoint.class, conflict.getTransportClass());
		Assertions.assertTrue(conflict.getMessage().contains("already owned"));
	}

	@NonNull
	private static WeakReference<Soklet> abandonOwner(
			@NonNull RaceHttpEndpoint transport) {
		Soklet original = Soklet.fromConfig(config(transport,
				completeResolver()).build());
		return new WeakReference<>(original);
	}

	private static void awaitCollected(@NonNull WeakReference<?> reference)
			throws InterruptedException {
		for (int attempt = 0; attempt < 100; attempt++) {
			// Modest pressure makes this deterministic on collectors which otherwise
			// decline an explicit collection because the heap is almost empty.
			byte[][] pressure = new byte[8][];
			for (int index = 0; index < pressure.length; index++)
				pressure[index] = new byte[256 * 1_024];
			System.gc();
			System.runFinalization();
			if (reference.get() == null)
				return;
			Thread.sleep(10);
		}
	}

	private static SokletConfig.@NonNull Builder config(@NonNull RaceHttpEndpoint http,
			@NonNull ResourceMethodResolver resolver) {
		return SokletConfig.withHttpServer(http)
				.resourceMethodResolver(resolver);
	}

	@NonNull
	private static ResourceMethodResolver completeResolver() {
		return ResourceMethodResolver.fromClasses(Set.of(RaceResource.class));
	}

	@NonNull
	private static InternalLifecyclePolicy shortCancellationPolicy() {
		return new InternalLifecyclePolicy(Optional.of(LONG_STARTUP), SHORT_PHASE,
				SHORT_PHASE, SHORT_PHASE);
	}

	@NonNull
	private static InternalLifecyclePolicy shortStartupTimeoutPolicy() {
		return new InternalLifecyclePolicy(Optional.of(Duration.ofMillis(150)),
				SHORT_PHASE, SHORT_PHASE, SHORT_PHASE);
	}

	@NonNull
	private ExecutorService newExecutor() {
		ExecutorService executor = Executors.newCachedThreadPool();
		this.executors.add(executor);
		return executor;
	}

	private static void joinShutdown(@NonNull SokletDirectLifecycle owner) {
		ShutdownResult result = owner.shutdown()
				.toCompletableFuture().join();
		owner.throwIfUnsuccessfulShutdown(result);
	}

	@Nullable
	private static Throwable captureFailure(@NonNull Runnable operation) {
		try {
			operation.run();
			return null;
		} catch (Throwable throwable) {
			return throwable;
		}
	}

	private static void awaitIgnoringInterrupts(@NonNull CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try {
				latch.await();
				break;
			} catch (InterruptedException exception) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	private static void awaitCondition(@NonNull BooleanSupplier condition,
			@NonNull String failureMessage) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline)
			Thread.sleep(1);
		Assertions.assertTrue(condition.getAsBoolean(), failureMessage);
	}

	private static boolean resolverLifecycleWaitIsBlocked() {
		for (var entry : Thread.getAllStackTraces().entrySet()) {
			Thread thread = entry.getKey();
			if (!thread.isAlive()
					|| !thread.getName().equals("soklet-framework-setup"))
				continue;
			for (StackTraceElement frame : entry.getValue()) {
				if (frame.getClassName().equals(
						DefaultResourceMethodResolver.class.getName())
						&& frame.getMethodName().equals("getResourceMethodsForLifecycle"))
					return true;
			}
		}
		return false;
	}

	private static boolean throwableGraphContains(@Nullable Throwable root,
			@NonNull Class<? extends Throwable> type) {
		if (root == null)
			return false;
		Set<Throwable> visited = Collections.newSetFromMap(
				new IdentityHashMap<>());
		ArrayDeque<Throwable> pending = new ArrayDeque<>();
		pending.add(root);
		while (!pending.isEmpty()) {
			Throwable current = pending.removeFirst();
			if (!visited.add(current))
				continue;
			if (type.isInstance(current))
				return true;
			if (current.getCause() != null)
				pending.addLast(current.getCause());
			for (Throwable suppressed : current.getSuppressed())
				pending.addLast(suppressed);
		}
		return false;
	}

	private static final class BlockingResolver implements ResourceMethodResolver {
		@NonNull
		private final ResourceMethodResolver delegate;
		@NonNull
		private final CountDownLatch entered;
		@NonNull
		private final CountDownLatch release;
		@NonNull
		private final CountDownLatch returned;

		private BlockingResolver(@NonNull ResourceMethodResolver delegate,
				@NonNull CountDownLatch entered, @NonNull CountDownLatch release,
				@NonNull CountDownLatch returned) {
			this.delegate = delegate;
			this.entered = entered;
			this.release = release;
			this.returned = returned;
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
			this.returned.countDown();
			return this.delegate.getResourceMethods();
		}
	}

	private static final class InterruptResponsiveStartGate {
		@NonNull
		private final CountDownLatch entered;
		@NonNull
		private final CountDownLatch interrupted;
		@NonNull
		private final CountDownLatch release;

		private InterruptResponsiveStartGate() {
			this.entered = new CountDownLatch(1);
			this.interrupted = new CountDownLatch(1);
			this.release = new CountDownLatch(1);
		}

		void block() {
			this.entered.countDown();
			try {
				this.release.await();
			} catch (InterruptedException exception) {
				this.interrupted.countDown();
				Thread.currentThread().interrupt();
			}
		}

		boolean awaitEntered() throws InterruptedException {
			return this.entered.await(2, TimeUnit.SECONDS);
		}

		boolean awaitInterrupted() throws InterruptedException {
			return this.interrupted.await(2, TimeUnit.SECONDS);
		}

		void release() {
			this.release.countDown();
		}
	}

	private static final class StartupFailureObserver
			implements LifecycleObserver {
		@NonNull
		private final AtomicReference<Throwable> failure;
		@NonNull
		private final CountDownLatch observed;

		private StartupFailureObserver() {
			this.failure = new AtomicReference<>();
			this.observed = new CountDownLatch(1);
		}

		@Override
		public void didFailToStartSoklet(@NonNull Soklet soklet,
				@NonNull Throwable throwable) {
			this.failure.compareAndSet(null, throwable);
			this.observed.countDown();
		}

		boolean awaitFailure() throws InterruptedException {
			return this.observed.await(2, TimeUnit.SECONDS);
		}

		@Nullable
		Throwable failure() {
			return this.failure.get();
		}
	}

	private static final class RaceHttpEndpoint implements HttpServer {
		@NonNull
		private final TransportIdentity identity;
		@NonNull
		private final AtomicInteger attachCalls;
		@NonNull
		private final AtomicInteger startCalls;
		@NonNull
		private final AtomicBoolean terminationSignalled;
		@NonNull
		private final AtomicReference<Runnable> attachAction;
		@NonNull
		private final AtomicReference<Runnable> startAction;
		@NonNull
		private final AtomicReference<RequestHandler> requestHandler;
		@NonNull
		private final AtomicReference<TransportTerminationSignal>
				terminationSignal;

		private RaceHttpEndpoint() {
			this.identity = TransportIdentity.create();
			this.attachCalls = new AtomicInteger();
			this.startCalls = new AtomicInteger();
			this.terminationSignalled = new AtomicBoolean();
			this.attachAction = new AtomicReference<>(() -> { });
			this.startAction = new AtomicReference<>(() -> { });
			this.requestHandler = new AtomicReference<>();
			this.terminationSignal = new AtomicReference<>();
		}

		void onAttach(@NonNull Runnable action) {
			this.attachAction.set(action);
		}

		void onStart(@NonNull Runnable action) {
			this.startAction.set(action);
		}

		int attachCalls() {
			return this.attachCalls.get();
		}

		int startCalls() {
			return this.startCalls.get();
		}

		void signalFailure(@NonNull Throwable failure) {
			this.terminationSignal.get().signalTerminationFailure(failure);
		}

		@NonNull
		Optional<HttpRequestResult> invoke(@NonNull String path) {
			RequestHandler handler = this.requestHandler.get();
			if (handler == null)
				return Optional.empty();
			AtomicReference<HttpRequestResult> result = new AtomicReference<>();
			handler.handleRequest(Request.withPath(HttpMethod.GET, path).build(),
					result::set);
			return Optional.ofNullable(result.get());
		}

		@Override
		@NonNull
		public TransportIdentity getTransportIdentity() {
			return this.identity;
		}

		@Override
		@NonNull
		public TransportRuntime attach(
				@NonNull HttpTransportAttachmentContext context,
				@NonNull StartupContext startupContext) {
			this.attachCalls.incrementAndGet();
			this.requestHandler.set(context.getAdmissionFencedRequestHandler());
			this.terminationSignal.set(context.getTerminationSignal());
			this.attachAction.get().run();
			return new TransportRuntime() {
				@Override
				public void start(@NonNull StartupContext context) {
					startCalls.incrementAndGet();
					startAction.get().run();
				}

				@Override
				public void quiesce(@NonNull ShutdownContext context) {
					terminate();
				}

				@Override
				public void force(@NonNull ShutdownContext context) {
					terminate();
				}
			};
		}

		private void terminate() {
			TransportTerminationSignal signal = this.terminationSignal.get();
			if (signal != null && this.terminationSignalled.compareAndSet(false, true))
				signal.signalTerminated();
		}
	}

	private static final class RaceSseEndpoint implements SseServer {
		@NonNull
		private final TransportIdentity identity;
		@NonNull
		private final AtomicBoolean terminationSignalled;
		@NonNull
		private final AtomicReference<Runnable> startAction;
		@NonNull
		private final AtomicReference<TransportTerminationSignal>
				terminationSignal;

		private RaceSseEndpoint() {
			this.identity = TransportIdentity.create();
			this.terminationSignalled = new AtomicBoolean();
			this.startAction = new AtomicReference<>(() -> { });
			this.terminationSignal = new AtomicReference<>();
		}

		void onStart(@NonNull Runnable action) {
			this.startAction.set(action);
		}

		@Override
		@NonNull
		public TransportIdentity getTransportIdentity() {
			return this.identity;
		}

		@Override
		@NonNull
		public TransportRuntime attach(
				@NonNull SseTransportAttachmentContext context,
				@NonNull StartupContext startupContext) {
			this.terminationSignal.set(context.getTerminationSignal());
			return new TransportRuntime() {
				@Override
				public void start(@NonNull StartupContext context) {
					startAction.get().run();
				}

				@Override
				public void quiesce(@NonNull ShutdownContext context) {
					terminate();
				}

				@Override
				public void force(@NonNull ShutdownContext context) {
					terminate();
				}
			};
		}

		@Override
		@NonNull
		public Optional<? extends SseBroadcaster> acquireBroadcaster(
				@Nullable ResourcePath resourcePath) {
			return Optional.empty();
		}
		private void terminate() {
			TransportTerminationSignal signal = this.terminationSignal.get();
			if (signal != null && this.terminationSignalled.compareAndSet(false, true))
				signal.signalTerminated();
		}
	}

	public static final class RaceResource {
		@GET("/race")
		@NonNull
		public String get() {
			return "race";
		}
	}
}
