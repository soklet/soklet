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
package com.soklet.internal.microhttp;

import com.soklet.StreamTerminationReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class StreamGracefulShutdownStateTests {

	@Test
	void queuedProducerObservesTheRequestWithoutCancelationOrCleanupElection() throws Exception {
		StreamLifecycleCoordinator coordinator = coordinator();
		HoldingExecutor executor = new HoldingExecutor();
		var reservation = coordinator.tryReserve();
		assertNotNull(reservation);
		AtomicBoolean entered = new AtomicBoolean();
		try {
			assertTrue(reservation.execute(executor, () -> {
				assertTrue(reservation.isGracefulShutdownRequested());
				assertFalse(reservation.isCanceled());
				assertFalse(Thread.currentThread().isInterrupted());
				assertEquals(0L, reservation.cleanupDeadlineNanos());
				assertTrue(reservation.completeProduction());
				assertTrue(reservation.completeTransport());
				reservation.complete();
				entered.set(true);
			}));
			assertFalse(reservation.isGracefulShutdownRequested());
			coordinator.requestGracefulShutdown();
			coordinator.requestGracefulShutdown();
			assertNull(coordinator.tryReserve());
			assertTrue(reservation.isGracefulShutdownRequested());
			assertFalse(reservation.isCanceled());
			assertEquals(1, coordinator.snapshot().queuedProducers());
			executor.runTask();
			assertTrue(entered.get());
			assertTrue(reservation.isGracefulShutdownRequested(), "The execution retains its monotonic state after completion");
			assertTrue(coordinator.awaitTermination(deadline()));
		} finally {
			coordinator.force();
			coordinator.retireQueuedTasks(executor.shutdownNow());
			reservation.complete();
			assertTrue(coordinator.awaitTermination(deadline()));
		}
	}

	@Test
	void sealingAdmissionAloneDoesNotRequestCompletion() throws Exception {
		StreamLifecycleCoordinator coordinator = coordinator();
		var reservation = coordinator.tryReserve();
		assertNotNull(reservation);
		try {
			coordinator.stopAdmission();
			assertFalse(reservation.isGracefulShutdownRequested());
			assertFalse(reservation.isCanceled());
			coordinator.requestGracefulShutdown();
			assertTrue(reservation.isGracefulShutdownRequested());
			coordinator.force();
			assertTrue(reservation.isGracefulShutdownRequested());
			assertEquals(StreamTerminationReason.SERVER_STOPPING, reservation.reason().orElseThrow());
		} finally {
			coordinator.force(); reservation.abandon();
			assertTrue(coordinator.awaitTermination(deadline()));
		}
	}

	@Test
	void completedCanceledAndFreshExecutionsDoNotInheritARequest() throws Exception {
		StreamLifecycleCoordinator coordinator = coordinator();
		var completed = coordinator.tryReserve();
		var canceled = coordinator.tryReserve();
		var live = coordinator.tryReserve();
		assertNotNull(completed); assertNotNull(canceled); assertNotNull(live);
		try {
			completed.executeInline(() -> {
				assertTrue(completed.completeProduction());
				assertTrue(completed.completeTransport());
				completed.complete();
			});
			canceled.cancel(StreamTerminationReason.CLIENT_DISCONNECTED, null);
			coordinator.requestGracefulShutdown();
			assertFalse(completed.isGracefulShutdownRequested());
			assertFalse(canceled.isGracefulShutdownRequested());
			assertTrue(live.isGracefulShutdownRequested());
		} finally {
			coordinator.force(); canceled.abandon(); live.abandon();
			assertTrue(coordinator.awaitTermination(deadline()));
		}
		StreamLifecycleCoordinator fresh = coordinator();
		var freshReservation = fresh.tryReserve(); assertNotNull(freshReservation);
		try { assertFalse(freshReservation.isGracefulShutdownRequested()); }
		finally { fresh.force(); freshReservation.abandon(); assertTrue(fresh.awaitTermination(deadline())); }
	}

	@Test
	void forceElectionSuppressesALateGracefulRequestBeforeEveryCancelationHookReturns() throws Exception {
		StreamLifecycleCoordinator coordinator = coordinator();
		var first = coordinator.tryReserve(); var second = coordinator.tryReserve();
		assertNotNull(first); assertNotNull(second);
		CountDownLatch forceHookEntered = new CountDownLatch(1);
		CountDownLatch releaseForceHook = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		first.bindTermination((reason, cause) -> {
			forceHookEntered.countDown();
			try { assertTrue(releaseForceHook.await(3, TimeUnit.SECONDS)); }
			catch (InterruptedException interruptedException) { Thread.currentThread().interrupt(); throw new AssertionError(interruptedException); }
		});
		Thread force = new Thread(() -> { try { coordinator.force(); } catch (Throwable throwable) { failure.set(throwable); } });
		try {
			force.start(); assertTrue(forceHookEntered.await(3, TimeUnit.SECONDS));
			assertFalse(second.isCanceled(), "Force has not reached the second reservation yet");
			coordinator.requestGracefulShutdown();
			assertFalse(first.isGracefulShutdownRequested());
			assertFalse(second.isGracefulShutdownRequested());
		} finally {
			releaseForceHook.countDown(); force.join(3000); assertFalse(force.isAlive());
			coordinator.force(); first.abandon(); second.abandon();
			assertTrue(coordinator.awaitTermination(deadline()));
		}
		assertNull(failure.get());
	}

	private static StreamLifecycleCoordinator coordinator() {
		return new StreamLifecycleCoordinator(4, 1, Duration.ofSeconds(5), ignored -> {});
	}
	private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(3); }

	private static final class HoldingExecutor extends AbstractExecutorService {
		private Runnable task;
		private boolean stopped;
		@Override public void execute(Runnable task) { this.task = task; }
		void runTask() { Runnable task = this.task; this.task = null; task.run(); }
		@Override public void shutdown() { this.stopped = true; }
		@Override public List<Runnable> shutdownNow() { this.stopped = true; Runnable task = this.task; this.task = null; return task == null ? List.of() : List.of(task); }
		@Override public boolean isShutdown() { return this.stopped; }
		@Override public boolean isTerminated() { return this.stopped && this.task == null; }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
	}
}
