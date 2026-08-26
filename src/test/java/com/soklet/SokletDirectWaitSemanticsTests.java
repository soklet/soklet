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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Regression coverage for interruptible and marked direct-owner joins. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class SokletDirectWaitSemanticsTests {
	@NonNull
	private static final Duration PHASE_BUDGET = Duration.ofSeconds(3);
	@NonNull
	private final Set<ExecutorService> executors =
			java.util.concurrent.ConcurrentHashMap.newKeySet();

	@AfterEach
	void tearDown() {
		List<ExecutorService> snapshot = List.copyOf(this.executors);
		for (ExecutorService executor : snapshot)
			executor.shutdownNow();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		boolean interrupted = false;
		int unterminated = 0;
		for (ExecutorService executor : snapshot) {
			while (!executor.isTerminated()) {
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0L)
					break;
				try {
					executor.awaitTermination(remaining,
							TimeUnit.NANOSECONDS);
				} catch (InterruptedException exception) {
					interrupted = true;
				}
			}
			if (!executor.isTerminated())
				unterminated++;
		}
		this.executors.clear();
		if (interrupted)
			Thread.currentThread().interrupt();
		Assertions.assertEquals(0, unterminated,
				"Wait-semantics test executors did not terminate");
	}

	@Test
	void interruptedWaiterCannotCancelPeerOrOwnerCompletion() throws Exception {
		ExecutorService executor = newExecutor(2);
		AtomicReference<Thread> interruptedThread = new AtomicReference<>();
		CountDownLatch waitersEntered = new CountDownLatch(2);
		try (WaitHarness harness = WaitHarness.create()) {
			harness.soklet().start();
			Future<WaitOutcome> interruptedWaiter = executor.submit(() -> {
				interruptedThread.set(Thread.currentThread());
				waitersEntered.countDown();
				try {
					return new WaitOutcome(harness.owner().awaitCompletion(), null);
				} catch (Throwable failure) {
					return new WaitOutcome(null, failure);
				}
			});
			Future<InternalShutdownResult> peerWaiter = executor.submit(() -> {
				waitersEntered.countDown();
				return harness.owner().awaitCompletion();
			});

			Assertions.assertTrue(waitersEntered.await(2, TimeUnit.SECONDS));
			interruptedThread.get().interrupt();
			WaitOutcome interrupted = interruptedWaiter.get(2, TimeUnit.SECONDS);
			Assertions.assertNull(interrupted.result());
			Assertions.assertInstanceOf(InterruptedException.class,
					interrupted.failure());
			Assertions.assertEquals(InternalLifecycleStateMachine.State.READY,
					harness.owner().state(),
					"An interrupted waiter must not publish shutdown intent");
			Assertions.assertTrue(harness.owner().result().isEmpty());
			Assertions.assertFalse(peerWaiter.isDone());

			CompletionStage<InternalShutdownResult> stage =
					harness.owner().shutdown();
			Assertions.assertTrue(harness.endpoint().awaitQuiesce());
			Assertions.assertFalse(peerWaiter.isDone());
			harness.endpoint().releaseTermination();
			InternalShutdownResult result = harness.owner().awaitCompletion();

			Assertions.assertSame(result,
					peerWaiter.get(2, TimeUnit.SECONDS));
			Assertions.assertSame(result,
					stage.toCompletableFuture().get(2, TimeUnit.SECONDS));
			Assertions.assertSame(result,
					harness.owner().result().orElseThrow());
			Assertions.assertTrue(result.isComplete());
		}
	}

	@Test
	void markerRejectsOnlyItsExactOwnerBeforePublication() throws Exception {
		ExecutorService executor = newExecutor(1);
		Object foreignOwner = new Object();
		CountDownLatch foreignWaitEntered = new CountDownLatch(1);
		try (WaitHarness harness = WaitHarness.create()) {
			harness.soklet().start();
			try (LifecycleExecutionContext.Scope ignored =
					harness.owner().enterExecution()) {
				Assertions.assertThrows(IllegalStateException.class,
						harness.owner()::awaitCompletion);
			}
			Assertions.assertEquals(InternalLifecycleStateMachine.State.READY,
					harness.owner().state(),
					"A rejected self-join must not publish shutdown intent");
			Assertions.assertTrue(harness.owner().result().isEmpty());
			Assertions.assertEquals(0, harness.endpoint().quiesceCalls());

			Future<InternalShutdownResult> foreignMarkedWaiter = executor.submit(() -> {
				try (LifecycleExecutionContext.Scope ignored =
						LifecycleExecutionContext.enter(foreignOwner)) {
					foreignWaitEntered.countDown();
					return harness.owner().awaitCompletion();
				}
			});
			Assertions.assertTrue(foreignWaitEntered.await(2, TimeUnit.SECONDS));
			Assertions.assertFalse(foreignMarkedWaiter.isDone(),
					"A foreign marker must be allowed to join this owner");

			harness.owner().shutdown();
			Assertions.assertTrue(harness.endpoint().awaitQuiesce());
			harness.endpoint().releaseTermination();
			InternalShutdownResult result = harness.owner().awaitCompletion();
			Assertions.assertSame(result,
					foreignMarkedWaiter.get(2, TimeUnit.SECONDS));

			try (LifecycleExecutionContext.Scope ignored =
					harness.owner().enterExecution()) {
				Assertions.assertSame(result, harness.owner().awaitCompletion(),
						"The marker diagnostic ends after terminal publication");
			}
		}
	}

	@Test
	void markedShutdownIsPromptAndReturnsTheCachedStage() throws Exception {
		try (WaitHarness harness = WaitHarness.create()) {
			harness.soklet().start();
			CompletionStage<InternalShutdownResult> stage;
			long began = System.nanoTime();
			try (LifecycleExecutionContext.Scope ignored =
					harness.owner().enterExecution()) {
				stage = harness.owner().shutdown();
				Assertions.assertSame(stage, harness.owner().shutdown());
			}
			Assertions.assertTrue(Duration.ofNanos(System.nanoTime() - began)
					.compareTo(Duration.ofSeconds(1)) < 0,
					"Nonblocking shutdown must return promptly from its own marker");
			Assertions.assertEquals(
					InternalLifecycleStateMachine.State.SHUTTING_DOWN,
					harness.owner().state());
			Assertions.assertTrue(harness.endpoint().awaitQuiesce());
			Assertions.assertFalse(stage.toCompletableFuture().isDone());
			Assertions.assertSame(stage, harness.owner().shutdown());

			harness.endpoint().releaseTermination();
			InternalShutdownResult result = harness.owner().awaitCompletion();
			Assertions.assertSame(result,
					stage.toCompletableFuture().get(2, TimeUnit.SECONDS));
			Assertions.assertSame(result,
					harness.owner().result().orElseThrow());
		}
	}

	@Test
	void concurrentStopAndCloseJoinOnceAndRestoreEntryInterrupt()
			throws Exception {
		ExecutorService executor = newExecutor(2);
		CountDownLatch callersReady = new CountDownLatch(2);
		CountDownLatch releaseCallers = new CountDownLatch(1);
		try (WaitHarness harness = WaitHarness.create()) {
			try {
				harness.soklet().start();
				Future<JoinOutcome> stop = executor.submit(() -> {
					callersReady.countDown();
					releaseCallers.await();
					return join(harness, harness.soklet()::stop, true);
				});
				Future<JoinOutcome> close = executor.submit(() -> {
					callersReady.countDown();
					releaseCallers.await();
					return join(harness, harness.soklet()::close, true);
				});
				Assertions.assertTrue(callersReady.await(2, TimeUnit.SECONDS));
				releaseCallers.countDown();
				Assertions.assertTrue(harness.endpoint().awaitQuiesce());
				Assertions.assertFalse(stop.isDone());
				Assertions.assertFalse(close.isDone());
				harness.endpoint().releaseTermination();

				JoinOutcome stopped = stop.get(2, TimeUnit.SECONDS);
				JoinOutcome closed = close.get(2, TimeUnit.SECONDS);
				Assertions.assertNull(stopped.failure());
				Assertions.assertNull(closed.failure());
				Assertions.assertTrue(stopped.interruptedOnEntry());
				Assertions.assertTrue(stopped.interruptedOnReturn(),
						"stop() must restore the caller's entry interrupt");
				Assertions.assertTrue(closed.interruptedOnEntry());
				Assertions.assertTrue(closed.interruptedOnReturn(),
						"close() must restore the caller's entry interrupt");
				Assertions.assertSame(stopped.result(), closed.result());
				Assertions.assertSame(stopped.result(),
						harness.owner().result().orElseThrow());
				Assertions.assertTrue(stopped.result().isComplete());
				Assertions.assertEquals(1, harness.endpoint().quiesceCalls(),
						"Concurrent terminal joiners must share one shutdown phase");
				Assertions.assertEquals(0, harness.endpoint().forceCalls());
			} finally {
				releaseCallers.countDown();
			}
		}
	}

	@NonNull
	private ExecutorService newExecutor(int threads) {
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		this.executors.add(executor);
		return executor;
	}

	@NonNull
	private static JoinOutcome join(@NonNull WaitHarness harness,
			@NonNull Runnable operation, boolean interruptOnEntry) {
		Thread.interrupted();
		if (interruptOnEntry)
			Thread.currentThread().interrupt();
		boolean interruptedOnEntry = Thread.currentThread().isInterrupted();
		Throwable failure = null;
		try {
			operation.run();
		} catch (Throwable throwable) {
			failure = throwable;
		}
		boolean interruptedOnReturn = Thread.currentThread().isInterrupted();
		InternalShutdownResult result = harness.owner().result().orElse(null);
		Thread.interrupted();
		return new JoinOutcome(result, failure, interruptedOnEntry,
				interruptedOnReturn);
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

	@NonNull
	private static InternalLifecyclePolicy testPolicy() {
		return new InternalLifecyclePolicy(Optional.of(PHASE_BUDGET), PHASE_BUDGET,
				PHASE_BUDGET, PHASE_BUDGET);
	}

	private record WaitOutcome(@Nullable InternalShutdownResult result,
			@Nullable Throwable failure) { }

	private record JoinOutcome(@Nullable InternalShutdownResult result,
			@Nullable Throwable failure, boolean interruptedOnEntry,
			boolean interruptedOnReturn) { }

	private record WaitHarness(@NonNull Soklet soklet,
			@NonNull SokletDirectLifecycle owner,
			@NonNull GatedHttpEndpoint endpoint) implements AutoCloseable {
		@NonNull
		private static WaitHarness create() {
			GatedHttpEndpoint endpoint = new GatedHttpEndpoint();
			Soklet soklet = Soklet.fromConfig(SokletConfig.withHttpServer(endpoint)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(
							Set.of(WaitResource.class)))
					.internalLifecyclePolicy(testPolicy())
					.build());
			return new WaitHarness(soklet, soklet.getDirectLifecycle(), endpoint);
		}

		@Override
		public void close() throws Exception {
			boolean interrupted = Thread.interrupted();
			Throwable failure = null;
			try {
				this.owner.shutdown();
			} catch (Throwable shutdownFailure) {
				failure = retainFailure(failure, shutdownFailure);
			}
			try {
				this.endpoint.releaseTermination();
			} catch (Throwable releaseFailure) {
				failure = retainFailure(failure, releaseFailure);
			}
			try {
				this.owner.awaitCompletion();
			} catch (InterruptedException waitInterrupted) {
				interrupted = true;
				failure = retainFailure(failure, waitInterrupted);
			} catch (Throwable awaitFailure) {
				failure = retainFailure(failure, awaitFailure);
			}
			try {
				this.soklet.close();
			} catch (Throwable sokletFailure) {
				failure = retainFailure(failure, sokletFailure);
			}
			if (interrupted)
				Thread.currentThread().interrupt();
			throwFailure(failure);
		}
	}

	private static final class GatedHttpEndpoint
			implements HttpServer, InternalHttpTransportEndpoint {
		@NonNull
		private final InternalTransportIdentity identity =
				InternalTransportIdentity.create();
		@NonNull
		private final AtomicBoolean started = new AtomicBoolean();
		@NonNull
		private final AtomicBoolean terminationReleased = new AtomicBoolean();
		@NonNull
		private final AtomicBoolean terminationSignalled = new AtomicBoolean();
		@NonNull
		private final AtomicInteger quiesceCalls = new AtomicInteger();
		@NonNull
		private final AtomicInteger forceCalls = new AtomicInteger();
		@NonNull
		private final CountDownLatch quiesceEntered = new CountDownLatch(1);
		@NonNull
		private final AtomicReference<InternalTransportTerminationSignal>
				terminationSignal = new AtomicReference<>();

		@Override
		@NonNull
		public InternalTransportIdentity identity() {
			return this.identity;
		}

		@Override
		@NonNull
		public InternalTransportRuntime attach(
				@NonNull InternalTransportAttachmentContext<RequestHandler> context,
				@NonNull InternalStartupContext startupContext) {
			this.terminationSignal.set(context.terminationSignal());
			return new InternalTransportRuntime() {
				@Override
				public void start(@NonNull InternalStartupContext context) {
					started.set(true);
				}

				@Override
				public void quiesce(@NonNull InternalShutdownContext context) {
					quiesceCalls.incrementAndGet();
					quiesceEntered.countDown();
					signalIfReleased();
				}

				@Override
				public void force(@NonNull InternalShutdownContext context) {
					forceCalls.incrementAndGet();
					signalIfReleased();
				}
			};
		}

		@Override
		public void start() {
			throw new AssertionError("Direct endpoint startup uses its runtime");
		}

		@Override
		public void stop() {
			releaseTermination();
		}

		@Override
		@NonNull
		public Boolean isStarted() {
			return this.started.get();
		}

		@Override
		public void initialize(@NonNull SokletConfig sokletConfig,
				@NonNull RequestHandler requestHandler) {
			throw new AssertionError("Direct endpoint attachment uses attach(...)");
		}

		boolean awaitQuiesce() throws InterruptedException {
			return this.quiesceEntered.await(2, TimeUnit.SECONDS);
		}

		int quiesceCalls() {
			return this.quiesceCalls.get();
		}

		int forceCalls() {
			return this.forceCalls.get();
		}

		void releaseTermination() {
			this.terminationReleased.set(true);
			signalIfReleased();
		}

		private void signalIfReleased() {
			InternalTransportTerminationSignal signal = this.terminationSignal.get();
			if (this.terminationReleased.get() && signal != null
					&& this.terminationSignalled.compareAndSet(false, true)) {
				this.started.set(false);
				signal.signalTerminated();
			}
		}
	}

	public static final class WaitResource {
		@GET("/wait")
		@NonNull
		public String get() {
			return "wait";
		}
	}
}
