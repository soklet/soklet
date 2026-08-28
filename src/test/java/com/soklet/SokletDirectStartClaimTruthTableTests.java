/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
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

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Truth-table coverage for the direct owner's one-shot start claim. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class SokletDirectStartClaimTruthTableTests {
	@Test
	void successfulStartThenStopRejectsLaterStartWithPlainClaimFailure() {
		TruthHttpEndpoint http = new TruthHttpEndpoint();
		try (Soklet soklet = Soklet.fromConfig(
				config(http, completeResolver()).build())) {
			soklet.start();
			ShutdownResult publicResult = soklet.shutdown()
					.toCompletableFuture().join();
			InternalShutdownResult ownerResult = soklet.getDirectLifecycle().result()
					.orElseThrow();
			Assertions.assertSame(ownerResult, publicResult.internalResult());

			IllegalStateException rejection = Assertions.assertThrows(
					IllegalStateException.class, soklet::start);

			Assertions.assertSame(IllegalStateException.class, rejection.getClass(),
					"A completed real start retains the ordinary one-shot claim error");
			Assertions.assertEquals(InternalStartupDisposition.READY,
					ownerResult.startupDisposition());
			Assertions.assertSame(ownerResult,
					soklet.getDirectLifecycle().result().orElseThrow());
			Assertions.assertEquals(1, http.attachCalls());
			Assertions.assertEquals(1, http.startCalls());
		}
	}

	@Test
	void failedStartRejectsLaterStartWithPlainClaimFailure() {
		TruthHttpEndpoint http = new TruthHttpEndpoint();
		try (Soklet soklet = Soklet.fromConfig(
				config(http, emptyResolver()).build())) {
			SokletStartupException firstFailure = Assertions.assertThrows(
					SokletStartupException.class, soklet::start);
			InternalShutdownResult ownerResult = firstFailure
					.getInternalShutdownResult();

			IllegalStateException rejection = Assertions.assertThrows(
					IllegalStateException.class, soklet::start);

			Assertions.assertSame(IllegalStateException.class, rejection.getClass(),
					"A failed real start still consumes the ordinary one-shot claim");
			Assertions.assertEquals(InternalStartupDisposition.FAILED,
					firstFailure.getInternalStartupDisposition());
			Assertions.assertSame(ownerResult,
					soklet.getDirectLifecycle().result().orElseThrow());
			Assertions.assertEquals(0, http.attachCalls());
			Assertions.assertEquals(0, http.startCalls());
		}
	}

	@Test
	void closeWinningNewMakesLaterStartReplayExactNotAttemptedResult() {
		TruthHttpEndpoint http = new TruthHttpEndpoint();
		try (Soklet soklet = Soklet.fromConfig(
				config(http, completeResolver()).build())) {
			soklet.close();
			InternalShutdownResult ownerResult = soklet.getDirectLifecycle().result()
					.orElseThrow();

			SokletStartupException rejection = Assertions.assertThrows(
					SokletStartupException.class, soklet::start);

			Assertions.assertEquals(InternalStartupDisposition.NOT_ATTEMPTED,
					rejection.getInternalStartupDisposition());
			Assertions.assertSame(ownerResult,
					rejection.getInternalShutdownResult(),
					"The losing start must replay the shutdown owner's exact result");
			Assertions.assertEquals(0, http.attachCalls());
			Assertions.assertEquals(0, http.startCalls());
		}
	}

	@Test
	void startRacingNewOriginShutdownWaitsForExactNotAttemptedResult()
			throws Exception {
		CountDownLatch observerLaunchEntered = new CountDownLatch(1);
		CountDownLatch releaseObserverLaunch = new CountDownLatch(1);
		CountDownLatch lifecycleWorkersFinished = new CountDownLatch(2);
		CountDownLatch terminalWaitEntered = new CountDownLatch(1);
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> {
			if (name.equals("soklet-lifecycle-observer")) {
				observerLaunchEntered.countDown();
				awaitIgnoringInterrupts(releaseObserverLaunch);
			}
			Thread worker = new Thread(() -> {
				try {
					task.run();
				} finally {
					lifecycleWorkersFinished.countDown();
				}
			}, "start-claim-truth-" + name);
			worker.setDaemon(true);
			worker.start();
		});
		TruthHttpEndpoint ownerHttp = new TruthHttpEndpoint();
		SokletConfig ownerConfig = config(ownerHttp, completeResolver())
				.lifecycleObserver(new LifecycleObserver() { })
				.build();
		Soklet callbackSoklet = Soklet.fromConfig(config(new TruthHttpEndpoint(),
				completeResolver()).build());
		SokletDirectLifecycle owner = new SokletDirectLifecycle(callbackSoklet,
				ownerConfig, new SokletFrameworkSetup(ownerConfig), NanoClock.system(),
				workers, () -> { }, () -> { }, terminalWaitEntered::countDown);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try (TruthRaceCleanup ignored = new TruthRaceCleanup(
				releaseObserverLaunch, lifecycleWorkersFinished, executor,
				callbackSoklet, owner)) {
			Future<?> shutdown = executor.submit(owner::requestShutdownIntent);
			Assertions.assertTrue(observerLaunchEntered.await(2, TimeUnit.SECONDS),
					"NEW-origin shutdown did not reach the terminal-publication gate");
			Assertions.assertEquals(InternalLifecycleStateMachine.State.SHUTTING_DOWN,
					owner.state());
			Assertions.assertTrue(owner.result().isEmpty(),
					"The gate must hold shutdown before its terminal result is visible");

			Future<Throwable> racingStart = executor.submit(() ->
					captureFailure(owner::start));
			Assertions.assertTrue(terminalWaitEntered.await(2, TimeUnit.SECONDS),
					"The losing start did not wait for NEW-origin terminal publication");
			Assertions.assertFalse(racingStart.isDone(),
					"A racing start cannot substitute a generic immediate claim error");

			releaseObserverLaunch.countDown();
			shutdown.get(3, TimeUnit.SECONDS);
			Throwable racingFailure = racingStart.get(3, TimeUnit.SECONDS);
			Assertions.assertInstanceOf(SokletStartupException.class, racingFailure);
			SokletStartupException rejection =
					(SokletStartupException) racingFailure;
			InternalShutdownResult ownerResult = owner.result().orElseThrow();

			Assertions.assertEquals(InternalStartupDisposition.NOT_ATTEMPTED,
					rejection.getInternalStartupDisposition());
			Assertions.assertSame(ownerResult,
					rejection.getInternalShutdownResult(),
					"The racing start must wait for and replay the owner's exact result");
			Assertions.assertEquals(InternalLifecycleStateMachine.State.CLOSED,
					owner.state());
			Assertions.assertEquals(0, ownerHttp.attachCalls());
			Assertions.assertEquals(0, ownerHttp.startCalls());
			Assertions.assertTrue(lifecycleWorkersFinished.await(2, TimeUnit.SECONDS),
					"Injected lifecycle workers did not finish after publication");
		}
	}

	private record TruthRaceCleanup(@NonNull CountDownLatch releaseObserverLaunch,
			@NonNull CountDownLatch lifecycleWorkersFinished,
			@NonNull ExecutorService executor, @NonNull Soklet callbackSoklet,
			@NonNull SokletDirectLifecycle owner) implements AutoCloseable {
		@Override
		public void close() throws Exception {
			boolean interrupted = Thread.interrupted();
			Throwable failure = null;
			this.releaseObserverLaunch.countDown();
			try {
				this.owner.shutdown();
			} catch (Throwable ownerFailure) {
				failure = retainFailure(failure, ownerFailure);
			}
			try {
				this.owner.awaitCompletion();
			} catch (InterruptedException waitInterrupted) {
				interrupted = true;
				failure = retainFailure(failure, waitInterrupted);
			} catch (Throwable awaitFailure) {
				failure = retainFailure(failure, awaitFailure);
			}
			this.executor.shutdownNow();
			try {
				if (!this.executor.awaitTermination(3, TimeUnit.SECONDS))
					failure = retainFailure(failure, new AssertionError(
							"Truth-table race executor did not terminate"));
			} catch (InterruptedException executorInterrupted) {
				interrupted = true;
				failure = retainFailure(failure, executorInterrupted);
			}
			try {
				if (!this.lifecycleWorkersFinished.await(3, TimeUnit.SECONDS))
					failure = retainFailure(failure, new AssertionError(
							"Injected lifecycle workers did not terminate"));
			} catch (InterruptedException workersInterrupted) {
				interrupted = true;
				failure = retainFailure(failure, workersInterrupted);
			}
			try {
				this.callbackSoklet.close();
			} catch (Throwable callbackFailure) {
				failure = retainFailure(failure, callbackFailure);
			}
			if (interrupted)
				Thread.currentThread().interrupt();
			throwFailure(failure);
		}
	}

	private static SokletConfig.@NonNull Builder config(
			@NonNull TruthHttpEndpoint http,
			@NonNull ResourceMethodResolver resolver) {
		return SokletConfig.withHttpServer(http)
				.resourceMethodResolver(resolver);
	}

	@NonNull
	private static ResourceMethodResolver completeResolver() {
		return ResourceMethodResolver.fromClasses(Set.of(TruthResource.class));
	}

	@NonNull
	private static ResourceMethodResolver emptyResolver() {
		return ResourceMethodResolver.fromMethods(Set.of());
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

	@Nullable
	private static Throwable retainFailure(@Nullable Throwable primary,
			@NonNull Throwable next) {
		if (primary == null)
			return next;
		if (primary != next)
			primary.addSuppressed(next);
		return primary;
	}

	private static void throwFailure(@Nullable Throwable failure)
			throws Exception {
		if (failure == null)
			return;
		if (failure instanceof Exception exception)
			throw exception;
		if (failure instanceof Error error)
			throw error;
		throw new RuntimeException(failure);
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

	private static final class TruthHttpEndpoint implements HttpServer {
		@NonNull
		private final TransportIdentity identity;
		@NonNull
		private final AtomicInteger attachCalls;
		@NonNull
		private final AtomicInteger startCalls;
		@NonNull
		private final AtomicBoolean terminationSignalled;
		@NonNull
		private final AtomicReference<TransportTerminationSignal>
				terminationSignal;

		private TruthHttpEndpoint() {
			this.identity = TransportIdentity.create();
			this.attachCalls = new AtomicInteger();
			this.startCalls = new AtomicInteger();
			this.terminationSignalled = new AtomicBoolean();
			this.terminationSignal = new AtomicReference<>();
		}

		int attachCalls() {
			return this.attachCalls.get();
		}

		int startCalls() {
			return this.startCalls.get();
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
			this.terminationSignal.set(context.getTerminationSignal());
			return new TransportRuntime() {
				@Override
				public void start(@NonNull StartupContext context) {
					startCalls.incrementAndGet();
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
			if (signal != null
					&& this.terminationSignalled.compareAndSet(false, true))
				signal.signalTerminated();
		}
	}

	public static final class TruthResource {
		@GET("/truth")
		@NonNull
		public String get() {
			return "truth";
		}
	}
}
