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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Controlled physical-work and deadline tests; no networking or timing sleeps. */
public class StreamLifecycleCoordinatorTests {
	private static final Duration GRACE = Duration.ofSeconds(30);

	@Test
	@org.junit.jupiter.api.Timeout(60)
	public void deadline_diagnostics_and_snapshots_exclude_retained_application_secrets() throws Exception {
		String secret = "streaming-private-request-payload-canary";
		IOException applicationFailure = new IOException(secret + "-message", new IOException(secret + "-cause"));
		applicationFailure.addSuppressed(new IOException(secret + "-suppressed"));
		AtomicInteger payloadRenderings = new AtomicInteger();
		AtomicReference<Throwable> signaledCause = new AtomicReference<>();
		AtomicReference<Throwable> diagnostic = new AtomicReference<>();
		CountDownLatch reported = new CountDownLatch(1);
		AtomicLong clock = new AtomicLong(100L);
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE, failure -> {
			diagnostic.set(failure);
			reported.countDown();
		}, clock::get);
		HoldingExecutor producers = new HoldingExecutor(false);
		StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
		StreamLifecycleCoordinator.Reservation.Work work = reservation.retainWork();
		Assertions.assertNotNull(work);
		try {
			reservation.bindTermination((reason, cause) -> signaledCause.set(cause));
			reservation.execute(producers, new Runnable() {
				@Override public void run() { Assertions.fail("Canceled queued producer must not enter"); }
				@Override public String toString() {
					payloadRenderings.incrementAndGet();
					return secret;
				}
			});
			Assertions.assertTrue(reservation.cancel(StreamTerminationReason.PRODUCER_FAILED, applicationFailure));
			reservation.complete();
			clock.set(reservation.cleanupDeadlineNanos());
			reservation.checkCleanupDeadline(clock.get());
			await(reported);

			var snapshot = coordinator.snapshot();
			var evidence = Assertions.assertInstanceOf(StreamLifecycleCoordinator.CleanupDeadlineExceededException.class,
					diagnostic.get());
			Assertions.assertEquals(1, snapshot.reservations());
			Assertions.assertEquals(1, snapshot.queuedProducers());
			Assertions.assertEquals(1L, snapshot.retainedWork());
			Assertions.assertEquals(1, snapshot.overdue());
			Assertions.assertEquals("producer-cleanup", evidence.getPhase());
			Assertions.assertEquals("QUEUED", evidence.getProducerState());
			Assertions.assertEquals("ABSENT", evidence.getCancelationState());
			Assertions.assertEquals("ABSENT", evidence.getTerminationState());
			Assertions.assertEquals("ABSENT", evidence.getPublisherState());
			Assertions.assertEquals(1L, evidence.getRetainedWork());
			Assertions.assertNull(evidence.getCause());
			Assertions.assertEquals(0, evidence.getSuppressed().length);
			StringWriter stackTrace = new StringWriter();
			evidence.printStackTrace(new PrintWriter(stackTrace));
			for (String rendered : List.of(snapshot.toString(), evidence.getMessage(), evidence.getLocalizedMessage(),
					evidence.toString(), stackTrace.toString()))
				Assertions.assertFalse(rendered.contains(secret), "Framework deadline evidence must contain only bounded metadata");
			Assertions.assertEquals(0, payloadRenderings.get(), "Diagnostic capture must not render retained application work");
			Assertions.assertSame(applicationFailure, reservation.cause().orElseThrow());
			Assertions.assertSame(applicationFailure, signaledCause.get(), "Application failure delivery remains exact");
		} finally {
			work.close();
			reservation.complete();
			finish(coordinator, producers);
		}
	}

	@Test
	public void retained_work_outlives_initializer_exit_and_terminal_publication_until_physical_release() throws Exception {
		AtomicLong clock = new AtomicLong(100L);
		AtomicReference<Throwable> evidence = new AtomicReference<>();
		CountDownLatch reported = new CountDownLatch(1);
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE, failure -> {
			evidence.set(failure);
			reported.countDown();
		}, clock::get);
		StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
		StreamLifecycleCoordinator.Reservation.Work work = reservation.retainWork();
		Assertions.assertNotNull(work);
		try {
			Assertions.assertTrue(reservation.executeInline(() -> {}));
			Assertions.assertEquals(0, coordinator.snapshot().runningProducers());
			Assertions.assertEquals(1L, coordinator.snapshot().retainedWork());
			Assertions.assertThrows(IllegalStateException.class, reservation::completeProduction);
			StreamLifecycleCoordinator.Reservation.Work afterInitialization = reservation.retainWork();
			Assertions.assertNotNull(afterInitialization, "An SSE connection can admit work after setup returns");
			afterInitialization.close();
			afterInitialization.close();
			Assertions.assertTrue(reservation.cancel(StreamTerminationReason.SERVER_STOPPING, null));
			reservation.complete();
			Assertions.assertNull(reservation.retainWork());
			Assertions.assertNull(coordinator.tryReserve());
			clock.set(reservation.cleanupDeadlineNanos());
			reservation.checkCleanupDeadline(clock.get());
			await(reported);
			var deadlineFailure = Assertions.assertInstanceOf(StreamLifecycleCoordinator.CleanupDeadlineExceededException.class, evidence.get());
			Assertions.assertEquals(1L, deadlineFailure.getRetainedWork());
			Assertions.assertEquals(1L, coordinator.snapshot().retainedWork());
			Assertions.assertEquals(1, coordinator.snapshot().overdue());
			coordinator.stopAdmission();
			Assertions.assertFalse(coordinator.awaitTermination(System.nanoTime()));
			work.close();
			work.close();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
			Assertions.assertEquals(0L, coordinator.snapshot().retainedWork());
		} finally {
			work.close();
			reservation.complete();
			coordinator.force();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
		}
	}

	@Test
	public void status_polling_is_not_blocked_by_another_streams_cleanup_transition() throws Exception {
		AtomicBoolean blockClock = new AtomicBoolean();
		CountDownLatch clockEntered = new CountDownLatch(1);
		CountDownLatch releaseClock = new CountDownLatch(1);
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(4, 1, GRACE, ignored -> {}, () -> {
			if (blockClock.compareAndSet(true, false)) {
				clockEntered.countDown();
				awaitUninterruptibly(releaseClock);
			}
			return 100L;
		});
		ExecutorService cleanupExecutor = Executors.newSingleThreadExecutor();
		ExecutorService pollingExecutor = Executors.newSingleThreadExecutor();
		StreamLifecycleCoordinator.Reservation blocked = reserve(coordinator);
		StreamLifecycleCoordinator.Reservation healthy = reserve(coordinator);
		StreamLifecycleCoordinator.Reservation completed = reserve(coordinator);
		StreamLifecycleCoordinator.Reservation canceled = reserve(coordinator);
		try {
			Assertions.assertTrue(completed.completeProduction());
			Assertions.assertTrue(canceled.cancel(StreamTerminationReason.RESPONSE_TIMEOUT, null));
			blockClock.set(true);
			var cleanup = cleanupExecutor.submit(blocked::beginCleanup);
			await(clockEntered); // beginCleanup holds the shared coordinator lock at this point.
			var statuses = pollingExecutor.submit(() -> List.of(
					healthy.isCanceled(), healthy.isProductionComplete(),
					completed.isCanceled(), completed.isProductionComplete(),
					canceled.isCanceled(), canceled.isProductionComplete()));
			Assertions.assertEquals(List.of(false, false, false, true, true, false),
					statuses.get(2, TimeUnit.SECONDS),
					"Status polling for unrelated streams must progress while a cleanup transition is paused");
			Assertions.assertEquals(1L, releaseClock.getCount(), "The coordinator transition is still paused");
			releaseClock.countDown();
			cleanup.get(2, TimeUnit.SECONDS);
		} finally {
			releaseClock.countDown();
			cleanupExecutor.shutdownNow();
			pollingExecutor.shutdownNow();
			Assertions.assertTrue(cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS));
			Assertions.assertTrue(pollingExecutor.awaitTermination(5, TimeUnit.SECONDS));
			for (StreamLifecycleCoordinator.Reservation reservation : List.of(blocked, healthy, completed, canceled))
				reservation.abandon();
			coordinator.stopAdmission();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
		}
	}

	@Test
	public void invalid_capacity_and_worker_counts_are_rejected() {
		for (int capacity : new int[]{0, -1, Integer.MAX_VALUE / 2 + 1, Integer.MAX_VALUE}) {
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> new StreamLifecycleCoordinator(capacity, 1, GRACE, ignored -> {}),
					"Invalid lifecycle capacity was accepted: " + capacity);
		}
		for (int workers : new int[]{0, -1, 2, Integer.MAX_VALUE}) {
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> new StreamLifecycleCoordinator(1, workers, GRACE, ignored -> {}),
					"Callback concurrency outside [1, capacity] was accepted: " + workers);
		}
	}

	@Test
	public void invalid_cleanup_grace_and_missing_dependencies_are_rejected() {
		for (Duration grace : List.of(Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(-1))) {
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> new StreamLifecycleCoordinator(1, 1, grace, ignored -> {}),
					"Non-positive cleanup grace was accepted: " + grace);
		}
		for (Duration grace : List.of(Duration.ofNanos(Long.MAX_VALUE).plusNanos(1),
				Duration.ofSeconds(Long.MAX_VALUE))) {
			IllegalArgumentException failure = Assertions.assertThrows(IllegalArgumentException.class,
					() -> new StreamLifecycleCoordinator(1, 1, grace, ignored -> {}),
					"Unrepresentable nanosecond grace was accepted: " + grace);
			Assertions.assertInstanceOf(ArithmeticException.class, failure.getCause());
		}
		Assertions.assertThrows(NullPointerException.class,
				() -> new StreamLifecycleCoordinator(1, 1, null, ignored -> {}));
		Assertions.assertThrows(NullPointerException.class,
				() -> new StreamLifecycleCoordinator(1, 1, GRACE, null));
		Assertions.assertThrows(NullPointerException.class,
				() -> new StreamLifecycleCoordinator(1, 1, GRACE, ignored -> {}, null));
	}

	@Test
	public void smallest_and_largest_nanosecond_graces_preserve_admission_boundaries() throws Exception {
		for (Duration grace : List.of(Duration.ofNanos(1), Duration.ofNanos(Long.MAX_VALUE))) {
			AtomicLong clock = new AtomicLong(100L);
			StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(2, 2, grace,
					ignored -> {}, clock::get);
			List<StreamLifecycleCoordinator.Reservation> reservations = new ArrayList<>();
			try {
				reservations.add(reserve(coordinator));
				reservations.add(reserve(coordinator));
				Assertions.assertNull(coordinator.tryReserve(), "Capacity itself remains the inclusive limit");
				reservations.get(0).beginCleanup();
				Assertions.assertEquals(clock.get() + grace.toNanos(),
						reservations.get(0).cleanupDeadlineNanos(), "Positive grace must not be rounded or clamped");
			} finally {
				for (StreamLifecycleCoordinator.Reservation reservation : reservations)
					reservation.abandon();
				coordinator.stopAdmission();
				Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
			}
		}
	}

	@Test
	public void producer_cleanup_expires_once_at_exact_deadline_across_signed_clock_wrap() throws Exception {
		AtomicLong clock = new AtomicLong(Long.MAX_VALUE - GRACE.toNanos() / 2);
		AtomicInteger signals = new AtomicInteger();
		AtomicInteger diagnostics = new AtomicInteger();
		AtomicReference<StreamTerminationReason> signaledReason = new AtomicReference<>();
		AtomicReference<Throwable> evidence = new AtomicReference<>();
		CountDownLatch reported = new CountDownLatch(1);
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE, failure -> {
			evidence.set(failure);
			diagnostics.incrementAndGet();
			reported.countDown();
		}, clock::get);
		HoldingExecutor producers = new HoldingExecutor(false);
		StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
		try {
			reservation.execute(producers, () -> Assertions.fail("The canceled queued producer must not enter"));
			reservation.bindTermination((reason, cause) -> {
				signaledReason.set(reason);
				signals.incrementAndGet();
				reservation.complete();
			});
			reservation.beginCleanup();
			long deadline = reservation.cleanupDeadlineNanos();
			Assertions.assertTrue(deadline < 0L, "The fixture must cross the signed nanoTime boundary");
			clock.set(deadline - 1L);
			reservation.checkCleanupDeadline(clock.get());
			Assertions.assertFalse(reservation.isCanceled());
			Assertions.assertEquals(0, coordinator.snapshot().overdue());
			Assertions.assertEquals(0, signals.get());
			clock.set(deadline);
			reservation.checkCleanupDeadline(clock.get());
			await(reported);
			Assertions.assertEquals(StreamTerminationReason.CLEANUP_TIMEOUT, signaledReason.get());
			Assertions.assertEquals(1, coordinator.snapshot().overdue());
			Assertions.assertEquals(1, coordinator.snapshot().queuedProducers());
			Assertions.assertNull(coordinator.tryReserve(), "Expiry must not release the queued physical envelope");
			StreamLifecycleCoordinator.CleanupDeadlineExceededException deadlineEvidence =
					Assertions.assertInstanceOf(StreamLifecycleCoordinator.CleanupDeadlineExceededException.class,
							evidence.get());
			Assertions.assertEquals("producer-cleanup", deadlineEvidence.getPhase());
			reservation.checkCleanupDeadline(deadline);
			reservation.checkCleanupDeadline(deadline + 1L);
			Assertions.assertEquals(1, signals.get());
			Assertions.assertEquals(1, diagnostics.get());
		} finally {
			reservation.complete();
			finish(coordinator, producers);
		}
	}

	@Test
	public void observer_cleanup_expires_once_at_exact_deadline_across_signed_clock_wrap() throws Exception {
		AtomicLong clock = new AtomicLong(Long.MAX_VALUE - GRACE.toNanos() * 2);
		CountDownLatch observerEntered = new CountDownLatch(1);
		CountDownLatch releaseObserver = new CountDownLatch(1);
		CountDownLatch reported = new CountDownLatch(1);
		AtomicInteger diagnostics = new AtomicInteger();
		AtomicReference<Throwable> evidence = new AtomicReference<>();
		AtomicBoolean productionComplete = new AtomicBoolean();
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE, failure -> {
			evidence.set(failure);
			diagnostics.incrementAndGet();
			reported.countDown();
		}, clock::get);
		HoldingExecutor producers = new HoldingExecutor(false);
		StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
		try {
			reservation.execute(producers, () -> {
				reservation.beginCleanup();
				productionComplete.set(reservation.completeProduction());
			});
			producers.runNext();
			Assertions.assertTrue(productionComplete.get());
			Assertions.assertTrue(reservation.completeTransport());
			clock.set(Long.MAX_VALUE - GRACE.toNanos() / 2);
			reservation.dispatchTermination(() -> {
				observerEntered.countDown();
				awaitUninterruptibly(releaseObserver);
			});
			reservation.complete();
			await(observerEntered);
			long deadline = reservation.observerCleanupDeadlineNanos();
			Assertions.assertTrue(deadline < 0L, "The fixture must cross the signed nanoTime boundary");
			clock.set(deadline - 1L);
			reservation.checkCleanupDeadline(clock.get());
			Assertions.assertEquals(0, coordinator.snapshot().overdue());
			clock.set(deadline);
			reservation.checkCleanupDeadline(clock.get());
			await(reported);
			Assertions.assertEquals(1, coordinator.snapshot().overdue());
			Assertions.assertEquals(1, coordinator.snapshot().callbacks());
			Assertions.assertFalse(reservation.isCanceled(), "Observer expiry cannot replace successful transport");
			Assertions.assertNull(coordinator.tryReserve(), "The live observer retains its admission slot");
			StreamLifecycleCoordinator.CleanupDeadlineExceededException deadlineEvidence =
					Assertions.assertInstanceOf(StreamLifecycleCoordinator.CleanupDeadlineExceededException.class,
							evidence.get());
			Assertions.assertEquals("termination-observer", deadlineEvidence.getPhase());
			reservation.checkCleanupDeadline(deadline);
			reservation.checkCleanupDeadline(deadline + 1L);
			Assertions.assertEquals(1, diagnostics.get());
		} finally {
			releaseObserver.countDown();
			reservation.complete();
			finish(coordinator, producers);
		}
	}

	@Test
	public void blocked_owner_finalization_expires_without_releasing_physical_work() throws Exception {
		AtomicLong clock = new AtomicLong(100L);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch signaled = new CountDownLatch(1);
		CountDownLatch diagnostic = new CountDownLatch(1);
		AtomicReference<Thread> owner = new AtomicReference<>();
		AtomicReference<Thread> finishedOn = new AtomicReference<>();
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE,
				failure -> diagnostic.countDown(), clock::get);
		ExecutorService producers = Executors.newSingleThreadExecutor();
		try {
			StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
			reservation.bindTermination((reason, cause) -> {
				Assertions.assertEquals(StreamTerminationReason.CLEANUP_TIMEOUT, reason);
				reservation.dispatchTermination(signaled::countDown);
				reservation.complete();
			});
			reservation.execute(producers, () -> {
				owner.set(Thread.currentThread());
				reservation.beginCleanup();
				entered.countDown();
				awaitUninterruptibly(release);
				finishedOn.set(Thread.currentThread());
			});
			await(entered);
			long deadline = reservation.cleanupDeadlineNanos();
			clock.set(deadline - 1L);
			reservation.beginCleanup();
			Assertions.assertEquals(deadline, reservation.cleanupDeadlineNanos(), "grace never restarts");
			reservation.checkCleanupDeadline(clock.get());
			Assertions.assertFalse(reservation.isCanceled());
			clock.set(deadline);
			reservation.checkCleanupDeadline(clock.get());
			await(signaled);
			await(diagnostic);
			Assertions.assertEquals(1, coordinator.snapshot().runningProducers());
			Assertions.assertEquals(1, coordinator.snapshot().overdue());
			Assertions.assertNull(coordinator.tryReserve(), "timeout does not manufacture capacity");
			coordinator.stopAdmission();
			Assertions.assertFalse(coordinator.awaitTermination(System.nanoTime()));
			release.countDown();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
			Assertions.assertSame(owner.get(), finishedOn.get(), "blocked finalization stays on owner");
			Assertions.assertEquals(0, coordinator.snapshot().reservations());
			Assertions.assertTrue(coordinator.isTerminated());
		} finally {
			release.countDown();
			finish(coordinator, producers);
		}
	}

	@Test
	public void four_stuck_workers_hold_later_well_behaved_cancelations_within_admission() throws Exception {
		CountDownLatch firstFourEntered = new CountDownLatch(4);
		CountDownLatch releaseWorkers = new CountDownLatch(1);
		AtomicInteger laterCalls = new AtomicInteger();
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(8, 4, GRACE, ignored -> {});
		List<StreamLifecycleCoordinator.Reservation> reservations = new ArrayList<>();
		try {
			for (int i = 0; i < 8; i++) {
				int index = i;
				StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
				reservations.add(reservation);
				reservation.bindTermination((reason, cause) -> {
					reservation.dispatchCallbacks(() -> {
						if (index < 4) {
							firstFourEntered.countDown();
							awaitUninterruptibly(releaseWorkers);
						} else laterCalls.incrementAndGet();
					});
					reservation.complete();
				});
				reservation.cancel(StreamTerminationReason.CLIENT_DISCONNECTED, null);
				if (i == 3) await(firstFourEntered);
			}
			Assertions.assertEquals(8, coordinator.snapshot().callbacks());
			Assertions.assertEquals(4, coordinator.snapshot().queuedCallbacks());
			Assertions.assertEquals(0, laterCalls.get());
			Assertions.assertNull(coordinator.tryReserve());
			for (StreamLifecycleCoordinator.Reservation reservation : reservations)
				reservation.checkCleanupDeadline(reservation.cleanupDeadlineNanos());
			Assertions.assertEquals(8, coordinator.snapshot().overdue(), "supervisor remains independent of workers");
			coordinator.force();
			Assertions.assertFalse(coordinator.awaitTermination(System.nanoTime()));
			releaseWorkers.countDown();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
			Assertions.assertEquals(4, laterCalls.get());
		} finally {
			releaseWorkers.countDown();
			coordinator.force();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
		}
	}

	@Test
	public void cancelation_does_not_retire_custom_executor_queue_without_removal_proof() throws Exception {
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE, ignored -> {});
		HoldingExecutor producers = new HoldingExecutor(false);
		AtomicInteger userEntries = new AtomicInteger();
		try {
			StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
			reservation.bindTermination((reason, cause) -> reservation.complete());
			reservation.execute(producers, userEntries::incrementAndGet);
			reservation.cancel(StreamTerminationReason.CLIENT_DISCONNECTED, null);
			Assertions.assertEquals(1, coordinator.snapshot().queuedProducers());
			Assertions.assertEquals(1, coordinator.snapshot().reservations());
			Assertions.assertNull(coordinator.tryReserve());
			coordinator.stopAdmission();
			Assertions.assertFalse(coordinator.awaitTermination(System.nanoTime()));
			producers.runNext();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
			Assertions.assertEquals(0, userEntries.get(), "late queued wrapper skips user entry");
		} finally {
			finish(coordinator, producers);
		}
	}

	@Test
	public void proven_queue_removal_retires_envelope_and_rejection_does_not_leak() throws Exception {
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(2, 1, GRACE, ignored -> {});
		HoldingExecutor producers = new HoldingExecutor(false);
		try {
			StreamLifecycleCoordinator.Reservation first = reserve(coordinator);
			first.bindTermination((reason, cause) -> first.complete());
			first.execute(producers, () -> Assertions.fail("removed producer ran"));
			coordinator.force();
			coordinator.retireQueuedTasks(producers.shutdownNow());
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
		} finally {
			finish(coordinator, producers);
		}

		StreamLifecycleCoordinator rejected = new StreamLifecycleCoordinator(1, 1, GRACE, ignored -> {});
		HoldingExecutor stopped = new HoldingExecutor(false);
		stopped.shutdown();
		StreamLifecycleCoordinator.Reservation reservation = reserve(rejected);
		try {
			Assertions.assertThrows(RejectedExecutionException.class, () -> reservation.execute(stopped, () -> {}));
			reservation.abandon();
			Assertions.assertEquals(0, rejected.snapshot().reservations());
		} finally {
			reservation.complete();
			finish(rejected, stopped);
		}
	}

	@Test
	public void inline_executor_is_rejected_before_producer_or_start_gate_entry() throws Exception {
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE, ignored -> {});
		HoldingExecutor producers = new HoldingExecutor(true);
		AtomicInteger entered = new AtomicInteger();
		StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
		try {
			Assertions.assertThrows(RejectedExecutionException.class,
					() -> reservation.execute(producers, entered::incrementAndGet));
			Assertions.assertEquals(0, entered.get());
			reservation.abandon();
			Assertions.assertEquals(0, coordinator.snapshot().reservations());
		} finally {
			reservation.complete();
			finish(coordinator, producers);
		}
	}

	@Test
	public void failed_cleanup_is_finished_work_and_diagnostics_cannot_block_supervision() throws Exception {
		CountDownLatch diagnosticEntered = new CountDownLatch(1);
		CountDownLatch releaseDiagnostic = new CountDownLatch(1);
		IOException failure = new IOException("cleanup failed");
		AtomicReference<Throwable> reported = new AtomicReference<>();
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE, throwable -> {
			reported.set(throwable);
			diagnosticEntered.countDown();
			awaitUninterruptibly(releaseDiagnostic);
		});
		try {
			StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
			reservation.dispatchCallbacks(() -> { throw new RuntimeException(failure); });
			reservation.complete();
			await(diagnosticEntered);
			Assertions.assertEquals(1, coordinator.snapshot().diagnostics(), "blocked observer remains explicitly counted");
			coordinator.stopAdmission();
			Assertions.assertFalse(coordinator.awaitTermination(System.nanoTime()));
			releaseDiagnostic.countDown();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
			Assertions.assertSame(failure, reported.get().getCause());
		} finally {
			releaseDiagnostic.countDown();
			coordinator.force();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
		}
	}

	@Test
	public void last_terminal_publication_remains_available_after_stop_admission() throws Exception {
		CountDownLatch producerEntered = new CountDownLatch(1);
		CountDownLatch publish = new CountDownLatch(1);
		CountDownLatch callbackEntered = new CountDownLatch(1);
		CountDownLatch releaseCallback = new CountDownLatch(1);
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE, ignored -> {});
		ExecutorService producers = Executors.newSingleThreadExecutor();
		try {
			StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
			reservation.execute(producers, () -> {
				producerEntered.countDown();
				awaitUninterruptibly(publish);
				reservation.dispatchCallbacks(() -> {
					callbackEntered.countDown();
					awaitUninterruptibly(releaseCallback);
				});
				reservation.complete();
			});
			await(producerEntered);
			coordinator.stopAdmission();
			Assertions.assertNull(coordinator.tryReserve());
			publish.countDown();
			await(callbackEntered);
			Assertions.assertFalse(coordinator.awaitTermination(System.nanoTime()));
			releaseCallback.countDown();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
		} finally {
			publish.countDown();
			releaseCallback.countDown();
			finish(coordinator, producers);
		}
	}

	@Test
	public void long_normal_wire_drain_does_not_consume_prompt_observer_grace() throws Exception {
		AtomicLong clock = new AtomicLong(100L);
		AtomicInteger diagnostics = new AtomicInteger();
		CountDownLatch observerEntered = new CountDownLatch(1);
		CountDownLatch releaseObserver = new CountDownLatch(1);
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE,
				ignored -> diagnostics.incrementAndGet(), clock::get);
		HoldingExecutor producers = new HoldingExecutor(false);
		try {
			StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
			reservation.execute(producers, () -> {
				reservation.beginCleanup();
				Assertions.assertTrue(reservation.completeProduction());
			});
			producers.runNext();
			clock.set(reservation.cleanupDeadlineNanos() + GRACE.toNanos());
			reservation.checkCleanupDeadline(clock.get());
			Assertions.assertFalse(reservation.isCanceled());
			Assertions.assertEquals(0, coordinator.snapshot().overdue());
			reservation.dispatchTermination(() -> {
				observerEntered.countDown();
				awaitUninterruptibly(releaseObserver);
			});
			await(observerEntered);
			Assertions.assertEquals(clock.get() + GRACE.toNanos(), reservation.observerCleanupDeadlineNanos());
			reservation.checkCleanupDeadline(clock.get());
			Assertions.assertEquals(0, coordinator.snapshot().overdue(), "healthy wire drain does not spend observer budget");
			reservation.complete();
			coordinator.stopAdmission();
			releaseObserver.countDown();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
			Assertions.assertEquals(0, diagnostics.get());
		} finally {
			releaseObserver.countDown();
			finish(coordinator, producers);
		}
	}

	@Test
	public void blocked_late_observer_expires_its_own_budget_and_keeps_reservation() throws Exception {
		AtomicLong clock = new AtomicLong(100L);
		CountDownLatch observerEntered = new CountDownLatch(1);
		CountDownLatch releaseObserver = new CountDownLatch(1);
		CountDownLatch overdueReported = new CountDownLatch(1);
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE,
				ignored -> overdueReported.countDown(), clock::get);
		HoldingExecutor producers = new HoldingExecutor(false);
		try {
			StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
			reservation.execute(producers, () -> {
				reservation.beginCleanup();
				Assertions.assertTrue(reservation.completeProduction());
			});
			producers.runNext();
			clock.set(reservation.cleanupDeadlineNanos() + GRACE.toNanos());
			reservation.dispatchTermination(() -> {
				observerEntered.countDown();
				awaitUninterruptibly(releaseObserver);
			});
			reservation.complete();
			await(observerEntered);
			clock.set(reservation.observerCleanupDeadlineNanos() - 1L);
			reservation.checkCleanupDeadline(clock.get());
			Assertions.assertEquals(0, coordinator.snapshot().overdue());
			clock.incrementAndGet();
			reservation.checkCleanupDeadline(clock.get());
			await(overdueReported);
			Assertions.assertEquals(1, coordinator.snapshot().overdue());
			Assertions.assertFalse(reservation.isCanceled(), "observer expiry does not rewrite normal delivery outcome");
			Assertions.assertNull(coordinator.tryReserve());
			coordinator.stopAdmission();
			Assertions.assertFalse(coordinator.awaitTermination(System.nanoTime()));
			releaseObserver.countDown();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
		} finally {
			releaseObserver.countDown();
			finish(coordinator, producers);
		}
	}

	@Test
	public void late_wire_failure_before_observer_dispatch_does_not_restart_producer_grace() throws Exception {
		AtomicLong clock = new AtomicLong(100L);
		AtomicInteger diagnostics = new AtomicInteger();
		CountDownLatch productionComplete = new CountDownLatch(1);
		CountDownLatch releaseEnvelope = new CountDownLatch(1);
		AtomicBoolean ownerInterrupted = new AtomicBoolean();
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE,
				ignored -> diagnostics.incrementAndGet(), clock::get);
		ExecutorService producers = Executors.newSingleThreadExecutor();
		try {
			StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
			reservation.bindTermination((reason, cause) -> {});
			reservation.execute(producers, () -> {
				reservation.beginCleanup();
				Assertions.assertTrue(reservation.completeProduction());
				productionComplete.countDown();
				awaitUninterruptibly(releaseEnvelope);
				ownerInterrupted.set(Thread.currentThread().isInterrupted());
			});
			await(productionComplete);
			clock.set(reservation.cleanupDeadlineNanos() + GRACE.toNanos());
			Assertions.assertTrue(reservation.cancel(StreamTerminationReason.CLIENT_DISCONNECTED, null));
			reservation.beginCleanup();
			reservation.checkCleanupDeadline(clock.get());
			Assertions.assertEquals(0, coordinator.snapshot().overdue());
			Assertions.assertEquals(0L, reservation.observerCleanupDeadlineNanos(), "observer phase has not begun");
			reservation.dispatchTermination(() -> {});
			reservation.complete();
			coordinator.stopAdmission();
			releaseEnvelope.countDown();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
			Assertions.assertFalse(ownerInterrupted.get(), "completed production is not interrupted by wire failure");
			Assertions.assertEquals(0, diagnostics.get());
		} finally {
			releaseEnvelope.countDown();
			finish(coordinator, producers);
		}
	}

	@Test
	public void deadline_evidence_survives_physical_retirement_during_its_signal() throws Exception {
		CountDownLatch reported = new CountDownLatch(1);
		AtomicReference<Throwable> evidence = new AtomicReference<>();
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE, failure -> {
			evidence.set(failure);
			reported.countDown();
		});
		HoldingExecutor producers = new HoldingExecutor(false);
		StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
		try {
			reservation.execute(producers, () -> Assertions.fail("queued producer entered"));
			reservation.bindTermination((reason, cause) -> {
				coordinator.retireQueuedTasks(producers.shutdownNow());
				reservation.complete();
			});
			reservation.beginCleanup();
			reservation.checkCleanupDeadline(reservation.cleanupDeadlineNanos());
			await(reported);
			Assertions.assertInstanceOf(StreamLifecycleCoordinator.CleanupDeadlineExceededException.class, evidence.get());
			coordinator.stopAdmission();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
			Assertions.assertEquals(0, coordinator.snapshot().reservations());
		} finally {
			reservation.complete();
			finish(coordinator, producers);
		}
	}

	@Test
	public void transport_election_preserves_its_winner_without_sealing_observer_publication() throws Exception {
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(2, 1, GRACE, ignored -> {});
		AtomicInteger observed = new AtomicInteger();
		StreamLifecycleCoordinator.Reservation delivered = reserve(coordinator);
		StreamLifecycleCoordinator.Reservation canceled = reserve(coordinator);
		try {
			Assertions.assertTrue(delivered.completeProduction());
			Assertions.assertTrue(delivered.completeTransport());
			Assertions.assertFalse(delivered.cancel(StreamTerminationReason.CLIENT_DISCONNECTED, null));
			delivered.dispatchTermination(observed::incrementAndGet);
			delivered.complete();
			Assertions.assertTrue(canceled.cancel(StreamTerminationReason.CLIENT_DISCONNECTED, null));
			Assertions.assertFalse(canceled.completeTransport());
			canceled.dispatchTermination(observed::incrementAndGet);
			canceled.complete();
			coordinator.stopAdmission();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
			Assertions.assertEquals(2, observed.get());
			Assertions.assertFalse(delivered.isCanceled());
			Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, canceled.reason().orElseThrow());
		} finally {
			delivered.complete();
			canceled.complete();
			coordinator.force();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
		}
	}

	@Test
	public void force_before_source_binding_replays_once_and_suppresses_entry() throws Exception {
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE, ignored -> {});
		HoldingExecutor producers = new HoldingExecutor(false);
		AtomicInteger signals = new AtomicInteger();
		StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
		try {
			coordinator.force();
			Assertions.assertEquals(1, coordinator.snapshot().reservations());
			reservation.bindTermination((reason, cause) -> {
				Assertions.assertEquals(StreamTerminationReason.SERVER_STOPPING, reason);
				signals.incrementAndGet();
				reservation.complete();
			});
			Assertions.assertFalse(reservation.execute(producers, () -> Assertions.fail("producer ran")));
			coordinator.force();
			Assertions.assertEquals(1, signals.get());
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
		} finally {
			reservation.complete();
			finish(coordinator, producers);
		}
	}

	@Test
	public void duplicate_executor_delivery_never_reenters_or_interrupts_a_reused_thread() throws Exception {
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE, ignored -> {});
		HoldingExecutor producers = new HoldingExecutor(false);
		AtomicInteger entries = new AtomicInteger();
		StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
		try {
			reservation.execute(producers, () -> {
				entries.incrementAndGet();
				reservation.complete();
			});
			Runnable wrapper = producers.takeNext();
			wrapper.run();
			wrapper.run();
			Assertions.assertEquals(1, entries.get());
			Assertions.assertFalse(reservation.cancel(StreamTerminationReason.CLIENT_DISCONNECTED, null));
			Assertions.assertFalse(Thread.currentThread().isInterrupted());
		} finally {
			finish(coordinator, producers);
		}
	}

	@Test
	public void inline_producer_stays_accounted_until_physical_exit_after_deadline() throws Exception {
		AtomicLong clock = new AtomicLong(100);
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE,
				ignored -> {}, clock::get);
		StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
		reservation.bindTermination((value, cause) -> {
			reason.set(value);
			reservation.complete();
		});
		Thread producer = new Thread(() -> reservation.executeInline(() -> {
			reservation.beginCleanup();
			entered.countDown();
			awaitUninterruptibly(release);
		}), "inline-producer-test");
		try {
			producer.start();
			await(entered);
			Assertions.assertEquals(1, coordinator.snapshot().runningProducers());
			reservation.checkCleanupDeadline(reservation.cleanupDeadlineNanos());
			Assertions.assertEquals(StreamTerminationReason.CLEANUP_TIMEOUT, reason.get());
			Assertions.assertEquals(1, coordinator.snapshot().reservations());
			Assertions.assertNull(coordinator.tryReserve());
			coordinator.stopAdmission();
			Assertions.assertFalse(coordinator.awaitTermination(System.nanoTime()));
		} finally {
			release.countDown();
			producer.join(5000);
			Assertions.assertFalse(producer.isAlive());
			coordinator.force();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
		}
	}

	@Test
	public void inline_producer_rejects_reentry_and_never_interrupts_its_reused_thread() throws Exception {
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE, ignored -> {});
		StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
		try {
			Assertions.assertTrue(reservation.executeInline(() -> {
				Assertions.assertThrows(IllegalStateException.class,
						() -> reservation.executeInline(() -> Assertions.fail("reentered")));
				Assertions.assertTrue(reservation.completeProduction());
			}));
			Assertions.assertEquals(0, coordinator.snapshot().runningProducers());
			reservation.bindTermination((reason, cause) -> reservation.complete());
			Thread signaler = new Thread(() -> reservation.cancel(StreamTerminationReason.CLIENT_DISCONNECTED, null));
			signaler.start();
			signaler.join(5000);
			Assertions.assertFalse(signaler.isAlive());
			Assertions.assertFalse(Thread.currentThread().isInterrupted());
		} finally {
			reservation.complete();
			coordinator.force();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
		}
	}

	@Test
	public void inline_producer_is_suppressed_when_cancelation_wins_before_entry() throws Exception {
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE, ignored -> {});
		StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
		try {
			reservation.cancel(StreamTerminationReason.SERVER_STOPPING, null);
			Assertions.assertFalse(reservation.executeInline(() -> Assertions.fail("entered canceled producer")));
			reservation.bindTermination((reason, cause) -> reservation.complete());
			Assertions.assertEquals(0, coordinator.snapshot().reservations());
		} finally {
			reservation.complete();
			coordinator.force();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
		}
	}

	@Test
	public void physical_exit_cannot_seal_a_cancelation_batch_before_signal_publication() throws Exception {
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE, ignored -> {});
		StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
		CountDownLatch signalEntered = new CountDownLatch(1);
		CountDownLatch publish = new CountDownLatch(1);
		AtomicInteger callbacks = new AtomicInteger();
		reservation.bindTermination((reason, cause) -> {
			signalEntered.countDown();
			awaitUninterruptibly(publish);
			reservation.dispatchCallbacks(callbacks::incrementAndGet);
		});
		Assertions.assertTrue(reservation.executeInline(() -> {}));
		Thread signaler = new Thread(() -> reservation.cancel(StreamTerminationReason.CLIENT_DISCONNECTED, null));
		try {
			signaler.start();
			await(signalEntered);
			reservation.complete();
			coordinator.stopAdmission();
			Assertions.assertEquals(1, coordinator.snapshot().reservations());
			publish.countDown();
			signaler.join(5000);
			Assertions.assertFalse(signaler.isAlive());
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
			Assertions.assertEquals(1, callbacks.get());
		} finally {
			publish.countDown();
			signaler.join(5000);
			reservation.complete();
			coordinator.force();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
		}
	}

	@Test
	public void pending_publisher_retains_admission_after_producer_exit_and_publication_seal() throws Exception {
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE, ignored -> {});
		StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
		AtomicReference<StreamLifecycleCoordinator.PublisherWork> publisher = new AtomicReference<>();
		try {
			Assertions.assertTrue(reservation.executeInline(() -> {
				publisher.set(reservation.retainPublisher());
				reservation.complete();
			}));
			Assertions.assertNotNull(publisher.get());
			Assertions.assertEquals(0, coordinator.snapshot().runningProducers());
			Assertions.assertEquals(1, coordinator.snapshot().reservations());
			Assertions.assertEquals(1, coordinator.snapshot().publisherLifetimes());
			Assertions.assertEquals(1, coordinator.snapshot().pendingPublisherAcquisitions());
			Assertions.assertNull(coordinator.tryReserve(), "producer exit cannot recycle pending acquisition capacity");
			coordinator.stopAdmission();
			Assertions.assertFalse(coordinator.awaitTermination(System.nanoTime()));

			publisher.get().subscriptionReceived();
			publisher.get().subscriptionReceived();
			Assertions.assertEquals(1, coordinator.snapshot().publisherLifetimes());
			Assertions.assertEquals(0, coordinator.snapshot().pendingPublisherAcquisitions());
			publisher.get().close();
			publisher.get().close();
			publisher.get().subscriptionReceived();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
			Assertions.assertEquals(0, coordinator.snapshot().publisherLifetimes());
		} finally {
			if (publisher.get() != null) publisher.get().close();
			reservation.complete();
			coordinator.force();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
		}
	}

	@Test
	public void pending_publisher_deadline_reports_evidence_without_releasing_the_obligation() throws Exception {
		AtomicLong clock = new AtomicLong(100L);
		AtomicReference<Throwable> diagnostic = new AtomicReference<>();
		CountDownLatch reported = new CountDownLatch(1);
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE, throwable -> {
			diagnostic.set(throwable);
			reported.countDown();
		}, clock::get);
		StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
		AtomicReference<StreamLifecycleCoordinator.PublisherWork> publisher = new AtomicReference<>();
		try {
			reservation.executeInline(() -> {
				// Entry won, then cancelation raced acquisition bookkeeping. The obligation remains necessary.
				reservation.cancel(StreamTerminationReason.APPLICATION_CANCELED, null);
				publisher.set(reservation.retainPublisher());
				reservation.complete();
			});
			Assertions.assertNotNull(publisher.get());
			reservation.checkCleanupDeadline(reservation.cleanupDeadlineNanos());
			await(reported);
			StreamLifecycleCoordinator.CleanupDeadlineExceededException evidence = Assertions.assertInstanceOf(
					StreamLifecycleCoordinator.CleanupDeadlineExceededException.class, diagnostic.get());
			Assertions.assertEquals("EXITED", evidence.getProducerState());
			Assertions.assertEquals("PENDING", evidence.getPublisherState());
			Assertions.assertEquals(1, evidence.getPublisherLifetimes());
			Assertions.assertEquals(1, evidence.getPendingPublisherAcquisitions());
			Assertions.assertTrue(evidence.getMessage().contains("publisher=PENDING"));
			Assertions.assertEquals(StreamTerminationReason.APPLICATION_CANCELED, reservation.reason().orElseThrow());
			Assertions.assertEquals(1, coordinator.snapshot().overdue());
			Assertions.assertEquals(1, coordinator.snapshot().publisherLifetimes());
			Assertions.assertNull(coordinator.tryReserve());
			coordinator.stopAdmission();
			Assertions.assertFalse(coordinator.awaitTermination(System.nanoTime()));
			publisher.get().subscriptionReceived();
			publisher.get().close();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
		} finally {
			if (publisher.get() != null) publisher.get().close();
			reservation.complete();
			coordinator.force();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
		}
	}

	@Test
	public void late_publisher_cleanup_failure_still_uses_bounded_diagnostics_after_producer_exit() throws Exception {
		AtomicReference<Throwable> diagnostic = new AtomicReference<>();
		CountDownLatch diagnosticEntered = new CountDownLatch(1);
		CountDownLatch releaseDiagnostic = new CountDownLatch(1);
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE, throwable -> {
			diagnostic.set(throwable);
			diagnosticEntered.countDown();
			awaitUninterruptibly(releaseDiagnostic);
		});
		StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
		AtomicReference<StreamLifecycleCoordinator.PublisherWork> publisher = new AtomicReference<>();
		IOException expected = new IOException("late publisher cancel failed");
		try {
			reservation.executeInline(() -> {
				publisher.set(reservation.retainPublisher());
				reservation.complete();
			});
			Assertions.assertNotNull(publisher.get());
			publisher.get().subscriptionReceived();
			reservation.reportCleanupFailure(expected);
			await(diagnosticEntered);
			reservation.reportCleanupFailure(new IOException("second diagnostic is suppressed"));
			publisher.get().close();
			Assertions.assertSame(expected, diagnostic.get());
			Assertions.assertEquals(0, coordinator.snapshot().publisherLifetimes());
			Assertions.assertEquals(1, coordinator.snapshot().diagnostics());
			Assertions.assertEquals(1, coordinator.snapshot().reservations());
			coordinator.stopAdmission();
			Assertions.assertFalse(coordinator.awaitTermination(System.nanoTime()));
			releaseDiagnostic.countDown();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
		} finally {
			releaseDiagnostic.countDown();
			if (publisher.get() != null) publisher.get().close();
			reservation.complete();
			coordinator.force();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
		}
	}

	@Test
	public void publisher_acquisition_requires_running_production_and_cannot_be_repeated() throws Exception {
		AtomicReference<Throwable> diagnostic = new AtomicReference<>();
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE, diagnostic::set);
		StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
		AtomicReference<StreamLifecycleCoordinator.PublisherWork> publisher = new AtomicReference<>();
		try {
			Assertions.assertThrows(IllegalStateException.class, reservation::retainPublisher);
			reservation.executeInline(() -> {
				publisher.set(reservation.retainPublisher());
				Assertions.assertThrows(IllegalStateException.class, reservation::retainPublisher);
				Assertions.assertThrows(IllegalStateException.class, reservation::completeProduction);
				publisher.get().subscriptionReceived();
				Assertions.assertThrows(IllegalStateException.class, reservation::completeProduction);
				publisher.get().close();
				Assertions.assertThrows(IllegalStateException.class, reservation::retainPublisher);
				Assertions.assertTrue(reservation.completeProduction());
				Assertions.assertThrows(IllegalStateException.class, reservation::retainPublisher);
				reservation.complete();
			});
			Assertions.assertNotNull(publisher.get());
			Assertions.assertTrue(reservation.isProductionComplete());
			Assertions.assertThrows(IllegalStateException.class, reservation::retainPublisher);
			coordinator.stopAdmission();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
			Assertions.assertNull(diagnostic.get(), "producer assertions cannot be swallowed as runtime failures");
		} finally {
			if (publisher.get() != null) publisher.get().close();
			reservation.complete();
			coordinator.force();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
		}
	}

	@Test
	public void canceled_before_entry_cannot_acquire_a_publisher_obligation() throws Exception {
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, GRACE, ignored -> {});
		StreamLifecycleCoordinator.Reservation reservation = reserve(coordinator);
		try {
			reservation.cancel(StreamTerminationReason.SERVER_STOPPING, null);
			Assertions.assertFalse(reservation.executeInline(reservation::retainPublisher));
			Assertions.assertThrows(IllegalStateException.class, reservation::retainPublisher);
			Assertions.assertEquals(0, coordinator.snapshot().publisherLifetimes());
			Assertions.assertEquals(0, coordinator.snapshot().pendingPublisherAcquisitions());
		} finally {
			reservation.complete();
			coordinator.force();
			Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)));
		}
	}

	private static StreamLifecycleCoordinator.Reservation reserve(StreamLifecycleCoordinator coordinator) {
		return java.util.Objects.requireNonNull(coordinator.tryReserve(), "expected capacity");
	}

	private static long deadlineAfterSeconds(long seconds) {
		return System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "controlled step did not complete");
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		while (true) {
			try {
				latch.await();
				break;
			} catch (InterruptedException ignored) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	private static void finish(StreamLifecycleCoordinator coordinator, ExecutorService producers) throws InterruptedException {
		coordinator.force();
		coordinator.retireQueuedTasks(producers.shutdownNow());
		Assertions.assertTrue(producers.awaitTermination(5, TimeUnit.SECONDS), "test producer did not exit");
		Assertions.assertTrue(coordinator.awaitTermination(deadlineAfterSeconds(5)), "test retained lifecycle work");
	}

	/** A custom executor with no queue-removal API beyond explicit shutdownNow proof. */
	private static final class HoldingExecutor extends AbstractExecutorService {
		private final List<Runnable> queued = new ArrayList<>();
		private final boolean inline;
		private boolean stopped;

		private HoldingExecutor(boolean inline) { this.inline = inline; }

		@Override public synchronized void shutdown() { this.stopped = true; }
		@Override public synchronized List<Runnable> shutdownNow() {
			this.stopped = true;
			List<Runnable> removed = List.copyOf(this.queued);
			this.queued.clear();
			return removed;
		}
		@Override public synchronized boolean isShutdown() { return this.stopped; }
		@Override public synchronized boolean isTerminated() { return this.stopped && this.queued.isEmpty(); }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
		@Override public synchronized void execute(Runnable command) {
			if (this.stopped) throw new RejectedExecutionException("test executor stopped");
			if (this.inline) command.run();
			else this.queued.add(command);
		}
		synchronized Runnable takeNext() { return this.queued.remove(0); }
		void runNext() { takeNext().run(); }
	}
}
