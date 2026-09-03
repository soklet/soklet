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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Regression coverage for direct-owner terminal/public-stage publication. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class SokletDirectTerminalPublicationTests {
	private static final LifecyclePolicy TEST_LIFECYCLE_POLICY =
			LifecyclePolicy.builder()
					.startupTimeout(Duration.ofSeconds(5))
					.startupCancelationTimeout(Duration.ofSeconds(2))
					.gracefulShutdownTimeout(Duration.ofSeconds(2))
					.forcedShutdownTimeout(Duration.ofSeconds(1))
					.build();
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
				"Terminal-publication test executors did not terminate");
	}

	@Test
	void concurrentAndPostClosedShutdownCallsShareOneStageAndResult()
			throws Exception {
		CountDownLatch releaseCallers = new CountDownLatch(1);
		ExecutorService callers = newExecutor(12);
		try (OwnerHarness harness = OwnerHarness.create()) {
			harness.owner().start();
			List<Future<CompletionStage<ShutdownResult>>> calls =
					new ArrayList<>();
			for (int index = 0; index < 24; index++)
				calls.add(callers.submit(() -> {
					Assertions.assertTrue(releaseCallers.await(10,
							TimeUnit.SECONDS),
							"Timed out waiting to release shutdown callers");
					return harness.owner().shutdown();
				}));

			releaseCallers.countDown();
			CompletionStage<ShutdownResult> stage =
					calls.get(0).get(3, TimeUnit.SECONDS);
			for (Future<CompletionStage<ShutdownResult>> call : calls)
				Assertions.assertSame(stage, call.get(3, TimeUnit.SECONDS));

			InternalShutdownResult result = harness.owner().awaitCompletion();
			Assertions.assertEquals(InternalLifecycleStateMachine.State.CLOSED,
					harness.owner().state());
			Assertions.assertSame(result,
					harness.owner().result().orElseThrow());
			Assertions.assertSame(stage, harness.owner().shutdown(),
					"Shutdown after CLOSED must retain the cached stage identity");
			Assertions.assertSame(stage, harness.owner().shutdown());
			Assertions.assertTrue(harness.launcher().awaitHandoff());
			Assertions.assertFalse(stage.toCompletableFuture().isDone(),
					"Private publication must precede the queued public handoff");
			Assertions.assertEquals(1, harness.workers().created(
					LifecycleWorkers.Role.PUBLIC_STAGE_HANDOFF));
			Assertions.assertEquals(1, harness.launcher().handoffCount());

			Thread handoff = harness.launcher().runNextHandoff();
			join(handoff);
			ShutdownResult publicResult = stage.toCompletableFuture()
					.get(2, TimeUnit.SECONDS);
			Assertions.assertSame(result,
					publicResult.internalResult());
			Assertions.assertSame(stage, harness.owner().shutdown());
			Assertions.assertEquals(1, harness.workers().created(
					LifecycleWorkers.Role.PUBLIC_STAGE_HANDOFF));
			Assertions.assertEquals(0, harness.launcher().handoffCount());
		} finally {
			releaseCallers.countDown();
		}
	}

	@Test
	void minimalViewAndDetachedMirrorsCannotControlOwner() throws Exception {
		try (OwnerHarness harness = OwnerHarness.create()) {
			harness.owner().start();
			CompletionStage<ShutdownResult> stage =
					harness.owner().shutdown();
			InternalShutdownResult result = harness.owner().awaitCompletion();
			Assertions.assertTrue(harness.launcher().awaitHandoff());

			ShutdownResult forged = ShutdownResult.fromInternal(
					new InternalShutdownResult(
							InternalShutdownDisposition.NOT_STARTED,
							InternalStartupDisposition.NOT_ATTEMPTED,
							List.of()));
			CompletableFuture<ShutdownResult> minimal =
					minimalFuture(stage);
			Assertions.assertThrows(UnsupportedOperationException.class,
					() -> minimal.complete(forged));
			Assertions.assertThrows(UnsupportedOperationException.class,
					() -> minimal.completeExceptionally(
							new IllegalStateException("forged failure")));
			Assertions.assertThrows(UnsupportedOperationException.class,
					() -> minimal.cancel(true));
			Assertions.assertThrows(UnsupportedOperationException.class,
					minimal::join);

			CompletableFuture<ShutdownResult> completedMirror =
					stage.toCompletableFuture();
			CompletableFuture<ShutdownResult> cancelledMirror =
					stage.toCompletableFuture();
			Assertions.assertTrue(completedMirror.complete(forged));
			Assertions.assertTrue(cancelledMirror.cancel(true));
			Assertions.assertSame(forged, completedMirror.join());
			Assertions.assertTrue(cancelledMirror.isCancelled());

			Thread handoff = harness.launcher().runNextHandoff();
			join(handoff);
			ShutdownResult publicResult = stage.toCompletableFuture()
					.get(2, TimeUnit.SECONDS);
			Assertions.assertSame(result,
					publicResult.internalResult());
			Assertions.assertSame(forged, completedMirror.join(),
					"A detached mirror keeps its independent mutation");
			Assertions.assertTrue(cancelledMirror.isCancelled());
			Assertions.assertSame(result,
					harness.owner().result().orElseThrow());
			Assertions.assertEquals(InternalLifecycleStateMachine.State.CLOSED,
					harness.owner().state());
		}
	}

	@Test
	void publicContinuationsPreserveExecutionAndFailureIsolation()
			throws Exception {
		AtomicReference<Thread> explicitWorker = new AtomicReference<>();
		ExecutorService explicitExecutor = newNamedExecutor(
				"terminal-public-explicit", explicitWorker);
		try (OwnerHarness harness = OwnerHarness.create()) {
			harness.owner().start();
			CompletionStage<ShutdownResult> stage =
					harness.owner().shutdown();
			InternalShutdownResult result = harness.owner().awaitCompletion();
			Assertions.assertTrue(harness.launcher().awaitHandoff());

			AtomicReference<Thread> handoffCallbackThread = new AtomicReference<>();
			AtomicReference<InternalLifecycleStateMachine.State> callbackState =
					new AtomicReference<>();
			AtomicReference<InternalShutdownResult> callbackResult =
					new AtomicReference<>();
			CompletionStage<Void> preRegistered = stage.thenAccept(value -> {
				handoffCallbackThread.set(Thread.currentThread());
				callbackState.set(harness.owner().state());
				callbackResult.set(harness.owner().result().orElseThrow());
				Assertions.assertSame(result, value.internalResult());
			});
			Thread handoff = harness.launcher().runNextHandoff();
			join(handoff);
			preRegistered.toCompletableFuture().get(2, TimeUnit.SECONDS);
			Assertions.assertSame(handoff, handoffCallbackThread.get());
			Assertions.assertEquals(InternalLifecycleStateMachine.State.CLOSED,
					callbackState.get());
			Assertions.assertSame(result, callbackResult.get());

			Thread registeringThread = Thread.currentThread();
			AtomicReference<Thread> lateThread = new AtomicReference<>();
			stage.thenAccept(ignored -> lateThread.set(Thread.currentThread()))
					.toCompletableFuture().get(2, TimeUnit.SECONDS);
			Assertions.assertSame(registeringThread, lateThread.get(),
					"A late non-async continuation may inline on its caller");

			AtomicReference<Thread> explicitThread = new AtomicReference<>();
			stage.thenAcceptAsync(ignored -> explicitThread.set(
					Thread.currentThread()), explicitExecutor)
					.toCompletableFuture().get(2, TimeUnit.SECONDS);
			Assertions.assertSame(explicitWorker.get(), explicitThread.get(),
					"The async continuation must use the exact supplied executor");

			RuntimeException exactFailure = new RuntimeException(
					"derived continuation failure");
			CompletionStage<Void> failedDerived = stage.thenAccept(ignored -> {
				throw exactFailure;
			});
			CompletionException observed = Assertions.assertThrows(
					CompletionException.class,
					() -> failedDerived.toCompletableFuture().join());
			Assertions.assertSame(exactFailure, observed.getCause());
			Assertions.assertSame(result,
					stage.toCompletableFuture().join().internalResult(),
					"A derived failure cannot poison the cached root");
			Assertions.assertSame(result,
					harness.owner().result().orElseThrow());
		}
	}

	@Test
	void blockedPreRegisteredContinuationCannotStrandPrivateOrPeerOwners()
			throws Exception {
		ExecutorService executor = newExecutor(4);
		CountDownLatch callbackEntered = new CountDownLatch(1);
		CountDownLatch releaseCallback = new CountDownLatch(1);
		try (OwnerHarness harness = OwnerHarness.create();
				Soklet peer = Soklet.fromConfig(
						config(new TerminalHttpEndpoint()).build())) {
			Thread handoff;
			try {
				harness.owner().start();
				peer.start();
				CompletionStage<ShutdownResult> stage =
						harness.owner().shutdown();
				AtomicReference<InternalLifecycleStateMachine.State> callbackState =
						new AtomicReference<>();
				AtomicReference<InternalShutdownResult> callbackResult =
						new AtomicReference<>();
				CompletionStage<Void> blocked = stage.thenAccept(value -> {
					callbackState.set(harness.owner().state());
					callbackResult.set(harness.owner().result().orElseThrow());
					Assertions.assertSame(value.internalResult(),
							callbackResult.get());
					callbackEntered.countDown();
					awaitIgnoringInterrupts(releaseCallback);
				});

				InternalShutdownResult result = harness.owner().awaitCompletion();
				Assertions.assertTrue(harness.launcher().awaitHandoff());
				handoff = harness.launcher().runNextHandoff();
				Assertions.assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
				Assertions.assertTrue(handoff.isAlive());
				Assertions.assertEquals(1, harness.workers().active(
						LifecycleWorkers.Role.PUBLIC_STAGE_HANDOFF));

				Future<InternalShutdownResult> privateJoin = executor.submit(
						harness.owner()::awaitCompletion);
				Assertions.assertSame(result,
						privateJoin.get(2, TimeUnit.SECONDS));
				Future<Throwable> peerStop = executor.submit(() ->
						captureFailure(peer::close));
				Assertions.assertNull(peerStop.get(3, TimeUnit.SECONDS),
						"A blocked callback for one owner cannot strand a peer owner");
				Assertions.assertEquals(InternalLifecycleStateMachine.State.CLOSED,
						peer.getDirectLifecycle().state());
				Assertions.assertEquals(InternalLifecycleStateMachine.State.CLOSED,
						callbackState.get());
				Assertions.assertSame(result, callbackResult.get());
				Assertions.assertSame(result, stage.toCompletableFuture()
						.get(2, TimeUnit.SECONDS).internalResult());
				Assertions.assertEquals(1, harness.workers().created(
						LifecycleWorkers.Role.PUBLIC_STAGE_HANDOFF));

				releaseCallback.countDown();
				join(handoff);
				blocked.toCompletableFuture().get(2, TimeUnit.SECONDS);
				Assertions.assertEquals(0, harness.workers().active(
						LifecycleWorkers.Role.PUBLIC_STAGE_HANDOFF));
			} finally {
				releaseCallback.countDown();
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
	private ExecutorService newNamedExecutor(@NonNull String name,
			@NonNull AtomicReference<Thread> worker) {
		ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, name);
			thread.setDaemon(true);
			worker.set(thread);
			return thread;
		});
		this.executors.add(executor);
		return executor;
	}

	private static void join(@NonNull Thread thread) throws InterruptedException {
		thread.join(TimeUnit.SECONDS.toMillis(3));
		Assertions.assertFalse(thread.isAlive(),
				"Public handoff worker did not finish within the test bound");
	}

	private static void joinForCleanup(@NonNull Thread thread)
			throws InterruptedException {
		thread.join(TimeUnit.SECONDS.toMillis(3));
		if (thread.isAlive()) {
			thread.interrupt();
			thread.join(TimeUnit.SECONDS.toMillis(3));
		}
		if (thread.isAlive())
			throw new AssertionError(
					"Public handoff worker survived bounded cleanup");
	}

	@Nullable
	private static Throwable retainFailure(@Nullable Throwable primary,
			@NonNull Throwable next) {
		if (primary == null)
			return next;
		addFailure(primary, next);
		return primary;
	}

	private static void addFailure(@NonNull Throwable primary,
			@NonNull Throwable secondary) {
		if (primary != secondary)
			primary.addSuppressed(secondary);
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

	@NonNull
	@SuppressWarnings("unchecked")
	private static <T> CompletableFuture<T> minimalFuture(
			@NonNull CompletionStage<T> stage) {
		Assertions.assertInstanceOf(CompletableFuture.class, stage);
		return (CompletableFuture<T>) stage;
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

	private static SokletConfig.@NonNull Builder config(
			@NonNull TerminalHttpEndpoint endpoint) {
		return SokletConfig.withHttpServer(endpoint)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(TerminalResource.class)))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY);
	}

	private record OwnerHarness(@NonNull Soklet callbackSoklet,
			@NonNull SokletDirectLifecycle owner,
			@NonNull LifecycleWorkers workers,
			@NonNull SelectiveHandoffLauncher launcher) implements AutoCloseable {
		@NonNull
		private static OwnerHarness create() {
			SelectiveHandoffLauncher launcher =
					new SelectiveHandoffLauncher();
			LifecycleWorkers workers = new LifecycleWorkers(launcher);
			SokletConfig ownerConfig = config(new TerminalHttpEndpoint()).build();
			Soklet callbackSoklet = Soklet.fromConfig(
					config(new TerminalHttpEndpoint()).build());
			try {
				SokletDirectLifecycle owner = new SokletDirectLifecycle(callbackSoklet,
						ownerConfig, new SokletFrameworkSetup(ownerConfig),
						NanoClock.system(), workers);
				return new OwnerHarness(callbackSoklet, owner, workers, launcher);
			} catch (RuntimeException | Error failure) {
				try {
					callbackSoklet.close();
				} catch (Throwable cleanupFailure) {
					addFailure(failure, cleanupFailure);
				}
				throw failure;
			}
		}

		@Override
		public void close() throws Exception {
			Throwable failure = null;
			try {
				this.owner.shutdown();
			} catch (Throwable shutdownFailure) {
				failure = retainFailure(failure, shutdownFailure);
			}
			try {
				this.owner.awaitCompletion();
			} catch (Throwable awaitFailure) {
				failure = retainFailure(failure, awaitFailure);
			}
			try {
				if (!this.launcher.awaitHandoff())
					throw new AssertionError(
							"Public handoff was not enqueued during cleanup");
				while (this.launcher.handoffCount() > 0)
					this.launcher.runNextHandoff();
			} catch (Throwable launchFailure) {
				failure = retainFailure(failure, launchFailure);
			}
			for (Thread handoff : this.launcher.handoffThreads()) {
				try {
					joinForCleanup(handoff);
				} catch (Throwable joinFailure) {
					failure = retainFailure(failure, joinFailure);
				}
			}
			try {
				this.callbackSoklet.close();
			} catch (Throwable callbackFailure) {
				failure = retainFailure(failure, callbackFailure);
			}
			throwFailure(failure);
		}
	}

	private static final class SelectiveHandoffLauncher
			implements LifecycleWorkers.Launcher {
		@NonNull
		private final ArrayDeque<Runnable> handoffs = new ArrayDeque<>();
		@NonNull
		private final List<Thread> handoffThreads = new ArrayList<>();
		@NonNull
		private final CountDownLatch handoffEnqueued = new CountDownLatch(1);

		@Override
		public void launch(@NonNull String name, @NonNull Runnable runnable) {
			if (name.equals("soklet-shutdown-result-handoff")) {
				synchronized (this.handoffs) {
					this.handoffs.addLast(runnable);
					this.handoffEnqueued.countDown();
					this.handoffs.notifyAll();
				}
				return;
			}
			Thread worker = new Thread(runnable, "terminal-" + name);
			worker.setDaemon(true);
			worker.start();
		}

		boolean awaitHandoff() throws InterruptedException {
			return this.handoffEnqueued.await(3, TimeUnit.SECONDS);
		}

		int handoffCount() {
			synchronized (this.handoffs) {
				return this.handoffs.size();
			}
		}

		@NonNull
		Thread runNextHandoff() {
			Runnable handoff;
			Thread worker;
			synchronized (this.handoffs) {
				handoff = this.handoffs.removeFirst();
				worker = new Thread(handoff,
						"terminal-public-handoff-test");
				worker.setDaemon(true);
				this.handoffThreads.add(worker);
				try {
					worker.start();
				} catch (RuntimeException | Error failure) {
					this.handoffThreads.remove(worker);
					this.handoffs.addFirst(handoff);
					throw failure;
				}
			}
			return worker;
		}

		@NonNull
		List<Thread> handoffThreads() {
			synchronized (this.handoffs) {
				return List.copyOf(this.handoffThreads);
			}
		}
	}

	private static final class TerminalHttpEndpoint implements HttpServer {
		@NonNull
		private final TransportIdentity identity = TransportIdentity.create();
		@NonNull
		private final AtomicBoolean terminationSignalled = new AtomicBoolean();
		@NonNull
		private final AtomicReference<TransportTerminationSignal>
				terminationSignal = new AtomicReference<>();

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
			this.terminationSignal.set(context.getTerminationSignal());
			return new TransportRuntime() {
				@Override
				public void start(@NonNull StartupContext context) {}

				@Override
				public void shutdownGracefully(@NonNull ShutdownContext context) {
					terminate();
				}

				@Override
				public void shutdownForcibly(@NonNull ShutdownContext context) {
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

	public static final class TerminalResource {
		@GET("/terminal")
		@NonNull
		public String get() {
			return "terminal";
		}
	}
}
