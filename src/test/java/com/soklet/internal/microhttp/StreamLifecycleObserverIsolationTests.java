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
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** Admitted observation must progress independently of other streams' blocked application hooks. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class StreamLifecycleObserverIsolationTests {
	private static final Duration GRACE = Duration.ofSeconds(30);

	@Test
	void blockedCancelBatchDoesNotQueueHealthyTerminationNotifications() throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		StreamLifecycleCoordinator owner = new StreamLifecycleCoordinator(4, 1, GRACE, ignored -> {});
		try {
			var blocked = reserve(owner);
			blocked.bindTermination((reason, cause) -> {
				blocked.dispatchCallbacks(() -> { entered.countDown(); hold(release); });
				blocked.complete();
			});
			blocked.cancel(StreamTerminationReason.CLIENT_DISCONNECTED, null);
			await(entered);
			assertHealthyNotifications(owner, 1);
			Assertions.assertEquals(1, owner.snapshot().callbacks());
			Assertions.assertEquals(0, owner.snapshot().queuedCallbacks());
			owner.force();
			Assertions.assertFalse(owner.awaitTermination(deadline(20)), "Blocked cancel work must remain retained");
		} finally { release.countDown(); drain(owner); }
	}

	@Test
	void blockedTerminationObserverDoesNotQueueOtherObserversOrCancelBatches() throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		StreamLifecycleCoordinator owner = new StreamLifecycleCoordinator(4, 1, GRACE, ignored -> {});
		try {
			var blocked = successful(owner);
			blocked.dispatchTermination(() -> { entered.countDown(); hold(release); });
			blocked.complete();
			await(entered);
			assertHealthyNotifications(owner, 1);
			CountDownLatch canceled = new CountDownLatch(1);
			var another = reserve(owner);
			another.dispatchCallbacks(canceled::countDown);
			another.complete();
			await(canceled);
			awaitReservations(owner, 1);
			owner.force();
			Assertions.assertFalse(owner.awaitTermination(deadline(20)), "Blocked observer must remain retained");
		} finally { release.countDown(); drain(owner); }
	}

	@Test
	void blockedDiagnosticDoesNotQueueOtherStreamsDiagnostics() throws Exception {
		IOException firstFailure = new IOException("first cleanup failure");
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicInteger delivered = new AtomicInteger();
		StreamLifecycleCoordinator owner = new StreamLifecycleCoordinator(4, 1, GRACE, failure -> {
			if (failure == firstFailure) { entered.countDown(); hold(release); }
			else delivered.incrementAndGet();
		});
		try {
			var blocked = reserve(owner);
			blocked.reportCleanupFailure(firstFailure);
			blocked.complete();
			await(entered);
			for (int index = 0; index < 12; index++) {
				var healthy = reserve(owner);
				healthy.reportCleanupFailure(new IOException("independent cleanup failure"));
				healthy.complete();
				awaitReservations(owner, 1);
				Assertions.assertEquals(index + 1, delivered.get());
			}
			Assertions.assertEquals(1, owner.snapshot().diagnostics());
			owner.force();
			Assertions.assertFalse(owner.awaitTermination(deadline(20)), "Blocked diagnostic must remain retained");
		} finally { release.countDown(); drain(owner); }
	}

	@Test
	void blockedUnadmittedObserverCannotStarveAdmittedTermination() throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		StreamLifecycleCoordinator owner = new StreamLifecycleCoordinator(4, 1, GRACE, ignored -> {});
		try {
			Assertions.assertTrue(owner.dispatchRejectionObserver(() -> { entered.countDown(); hold(release); }));
			await(entered);
			assertHealthyNotifications(owner, 0);
			Assertions.assertEquals(1, owner.snapshot().callbacks());
			owner.force();
			Assertions.assertFalse(owner.awaitTermination(deadline(20)), "Unadmitted observer is still physical work");
		} finally { release.countDown(); drain(owner); }
	}

	@Test
	void allBlockedObserversStillExhaustAdmissionAndRemainCountedThroughShutdown() throws Exception {
		int capacity = 3;
		CountDownLatch entered = new CountDownLatch(capacity), release = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		StreamLifecycleCoordinator owner = new StreamLifecycleCoordinator(capacity, 1, GRACE, ignored -> {});
		try {
			for (int index = 0; index < capacity; index++) {
				var blocked = successful(owner);
				blocked.dispatchTermination(() -> { calls.incrementAndGet(); entered.countDown(); hold(release); });
				blocked.complete();
			}
			await(entered);
			Assertions.assertNull(owner.tryReserve());
			Assertions.assertEquals(capacity, owner.snapshot().reservations());
			Assertions.assertEquals(capacity, owner.snapshot().callbacks());
			Assertions.assertEquals(0, owner.snapshot().queuedCallbacks());
			owner.force();
			Assertions.assertFalse(owner.awaitTermination(deadline(20)));
			Assertions.assertFalse(owner.isTerminated());
		} finally { release.countDown(); drain(owner); }
		Assertions.assertEquals(capacity, calls.get(), "Accepted observers must execute exactly once");
		Assertions.assertEquals(0, owner.snapshot().reservations());
		Assertions.assertEquals(0, owner.snapshot().callbacks());
	}

	@Test
	void concurrentRetirementAndReuseDoesNotDropAcceptedObservations() throws Exception {
		StreamLifecycleCoordinator owner = new StreamLifecycleCoordinator(1, 1, GRACE, ignored -> {});
		AtomicInteger terminations = new AtomicInteger(), diagnostics = new AtomicInteger();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		List<Thread> callers = new ArrayList<>();
		CountDownLatch start = new CountDownLatch(1);
		try {
			for (int index = 0; index < 4; index++) {
				Thread caller = new Thread(() -> {
					try {
						await(start);
						for (int iteration = 0; iteration < 25; iteration++) {
							long until = deadline(5000);
							StreamLifecycleCoordinator.Reservation reservation;
							while ((reservation = owner.tryReserve()) == null && System.nanoTime() - until < 0L)
								LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
							Assertions.assertNotNull(reservation, "Completed observations stranded the sole admission slot");
							try {
								reservation.bindCleanupFailureObserver(ignored -> diagnostics.incrementAndGet());
								reservation.dispatchTermination(terminations::incrementAndGet);
								reservation.reportCleanupFailure(new IOException("bounded reuse"));
							} finally { reservation.complete(); }
						}
					} catch (Throwable throwable) { failure.compareAndSet(null, throwable); }
				}, "observer-reuse-caller-" + index);
				callers.add(caller); caller.start();
			}
			start.countDown();
			for (Thread caller : callers) { caller.join(6000); Assertions.assertFalse(caller.isAlive()); }
			Assertions.assertNull(failure.get(), "Accepted observation failed during retirement/reuse");
			awaitReservations(owner, 0);
			Assertions.assertEquals(100, terminations.get());
			Assertions.assertEquals(100, diagnostics.get());
		} finally {
			start.countDown();
			for (Thread caller : callers) caller.join(6000);
			drain(owner);
		}
	}

	private static void assertHealthyNotifications(StreamLifecycleCoordinator owner, int retained) throws Exception {
		AtomicInteger delivered = new AtomicInteger();
		for (int index = 0; index < 12; index++) {
			var healthy = successful(owner);
			CountDownLatch observed = new CountDownLatch(1);
			healthy.dispatchTermination(() -> { delivered.incrementAndGet(); observed.countDown(); });
			healthy.complete();
			await(observed);
			awaitReservations(owner, retained);
			Assertions.assertEquals(index + 1, delivered.get());
		}
	}

	private static StreamLifecycleCoordinator.Reservation successful(StreamLifecycleCoordinator owner) {
		var reservation = reserve(owner);
		Assertions.assertTrue(reservation.executeInline(() -> Assertions.assertTrue(reservation.completeProduction())));
		Assertions.assertTrue(reservation.completeTransport());
		return reservation;
	}

	private static StreamLifecycleCoordinator.Reservation reserve(StreamLifecycleCoordinator owner) {
		var reservation = owner.tryReserve();
		Assertions.assertNotNull(reservation, "An unrelated blocked hook consumed healthy stream admission");
		return reservation;
	}

	private static void awaitReservations(StreamLifecycleCoordinator owner, int expected) {
		long deadline = deadline(3000);
		while (owner.snapshot().reservations() != expected && System.nanoTime() - deadline < 0L)
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
		Assertions.assertEquals(expected, owner.snapshot().reservations(), "Completed observation retained another stream's slot");
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		Assertions.assertTrue(latch.await(3, TimeUnit.SECONDS), "Independent observation did not enter");
	}

	private static void hold(CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try { latch.await(); break; }
			catch (InterruptedException ignored) { interrupted = true; }
		}
		if (interrupted) Thread.currentThread().interrupt();
	}

	private static long deadline(long millis) { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis); }
	private static void drain(StreamLifecycleCoordinator owner) throws InterruptedException {
		owner.force();
		Assertions.assertTrue(owner.awaitTermination(deadline(5000)), "Controlled physical work did not drain");
	}
}
