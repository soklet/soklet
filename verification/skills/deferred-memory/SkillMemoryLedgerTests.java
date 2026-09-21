/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet.internal.mcp.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static com.soklet.internal.mcp.skills.SkillMemoryLedger.Kind.RETAINED;
import static com.soklet.internal.mcp.skills.SkillMemoryLedger.Kind.TRANSIENT;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

/** Archived experiment tests, outside Maven test roots; not a current Skills gate. */
class SkillMemoryLedgerTests {
	private static final int WAIT_SECONDS = 10;

	@Test
	void requiresPositiveCeilings() {
		for (long[] ceilings : new long[][]{{0, 1}, {1, 0}, {-1, 1}, {1, -1}, {Long.MIN_VALUE, 1}})
			assertInvalid(() -> new SkillMemoryLedger(ceilings[0], ceilings[1]));
		assertSnapshot(new SkillMemoryLedger(1, 1), 0, 0);
	}

	@Test
	void zeroChargeReservationIsActiveAndCanLaterAcquireCharges() {
		SkillMemoryLedger ledger = new SkillMemoryLedger(2, 3);
		try (SkillMemoryLedger.Reservation reservation = ledger.reserve(0, 0)) {
			assertSnapshot(ledger, 0, 0);
			reservation.resize(2, 3);
			assertSnapshot(ledger, 2, 3);
			reservation.resize(0, 0);
			assertSnapshot(ledger, 0, 0);
		}
		assertSnapshot(ledger, 0, 0);
	}

	@Test
	void inclusiveCeilingsAdmitExactlyAndCloseRefundsExactlyOnce() {
		SkillMemoryLedger ledger = new SkillMemoryLedger(5, 7);
		SkillMemoryLedger.Reservation reservation = ledger.reserve(5, 7);
		assertSnapshot(ledger, 5, 7);
		assertLimit(RETAINED, () -> ledger.reserve(1, 0));
		assertLimit(TRANSIENT, () -> ledger.reserve(0, 1));
		reservation.close();
		reservation.close();
		assertSnapshot(ledger, 0, 0);
		try (SkillMemoryLedger.Reservation replacement = ledger.reserve(5, 7)) {
			assertSnapshot(ledger, 5, 7);
		}
	}

	@Test
	void negativeReservationsLeaveBothBucketsUnchanged() {
		SkillMemoryLedger ledger = new SkillMemoryLedger(10, 10);
		try (SkillMemoryLedger.Reservation existing = ledger.reserve(2, 3)) {
			for (long[] charge : new long[][]{{-1, 0}, {0, -1}, {Long.MIN_VALUE, Long.MAX_VALUE}}) {
				assertInvalid(() -> ledger.reserve(charge[0], charge[1]));
				assertSnapshot(ledger, 2, 3);
			}
		}
	}

	@Test
	void retainedExhaustionDoesNotAdmitEitherCharge() {
		SkillMemoryLedger ledger = new SkillMemoryLedger(10, 10);
		try (SkillMemoryLedger.Reservation existing = ledger.reserve(3, 4)) {
			assertLimit(RETAINED, () -> ledger.reserve(8, 1));
			assertSnapshot(ledger, 3, 4);
		}
		assertSnapshot(ledger, 0, 0);
	}

	@Test
	void transientExhaustionDoesNotLeakTheAdmissibleRetainedCharge() {
		SkillMemoryLedger ledger = new SkillMemoryLedger(10, 5);
		try (SkillMemoryLedger.Reservation existing = ledger.reserve(3, 4)) {
			assertLimit(TRANSIENT, () -> ledger.reserve(4, 2));
			assertSnapshot(ledger, 3, 4);
		}
		assertSnapshot(ledger, 0, 0);
	}

	@Test
	void resizeUsesAbsoluteChargesAndCanGrowShrinkOrReleaseEitherBucket() {
		SkillMemoryLedger ledger = new SkillMemoryLedger(10, 10);
		try (SkillMemoryLedger.Reservation reservation = ledger.reserve(4, 5)) {
			reservation.resize(7, 8);
			assertSnapshot(ledger, 7, 8);
			reservation.resize(2, 3);
			assertSnapshot(ledger, 2, 3);
			reservation.resize(2, 0);
			assertSnapshot(ledger, 2, 0);
			reservation.resize(0, 4);
			assertSnapshot(ledger, 0, 4);
		}
		assertSnapshot(ledger, 0, 0);
	}

	@Test
	void resizeCanSwapBucketUsageWithoutTemporarilyChargingBothVersions() {
		SkillMemoryLedger ledger = new SkillMemoryLedger(10, 10);
		try (SkillMemoryLedger.Reservation other = ledger.reserve(2, 2);
				SkillMemoryLedger.Reservation reservation = ledger.reserve(8, 2)) {
			assertSnapshot(ledger, 10, 4);
			reservation.resize(2, 8);
			assertSnapshot(ledger, 4, 10);
			reservation.resize(8, 2);
			assertSnapshot(ledger, 10, 4);
		}
	}

	@Test
	void rejectedRetainedResizePreservesBothOldChargesAndOtherReservations() {
		SkillMemoryLedger ledger = new SkillMemoryLedger(10, 10);
		try (SkillMemoryLedger.Reservation other = ledger.reserve(3, 2);
				SkillMemoryLedger.Reservation reservation = ledger.reserve(4, 5)) {
			assertLimit(RETAINED, () -> reservation.resize(8, 1));
			assertSnapshot(ledger, 7, 7);
			reservation.close();
			assertSnapshot(ledger, 3, 2);
		}
		assertSnapshot(ledger, 0, 0);
	}

	@Test
	void rejectedTransientResizeDoesNotCommitTheRetainedShrink() {
		SkillMemoryLedger ledger = new SkillMemoryLedger(10, 10);
		try (SkillMemoryLedger.Reservation other = ledger.reserve(3, 2);
				SkillMemoryLedger.Reservation reservation = ledger.reserve(4, 5)) {
			assertLimit(TRANSIENT, () -> reservation.resize(1, 9));
			assertSnapshot(ledger, 7, 7);
			reservation.close();
			assertSnapshot(ledger, 3, 2);
		}
		assertSnapshot(ledger, 0, 0);
	}

	@Test
	void negativeResizesPreserveTheActiveReservation() {
		SkillMemoryLedger ledger = new SkillMemoryLedger(10, 10);
		try (SkillMemoryLedger.Reservation reservation = ledger.reserve(2, 3)) {
			for (long[] charge : new long[][]{{-1, 0}, {0, -1}, {Long.MAX_VALUE, Long.MIN_VALUE}}) {
				assertInvalid(() -> reservation.resize(charge[0], charge[1]));
				assertSnapshot(ledger, 2, 3);
			}
			reservation.resize(4, 5);
			assertSnapshot(ledger, 4, 5);
		}
	}

	@Test
	void closedReservationsCannotBeReopenedEvenWithZeroCharges() {
		SkillMemoryLedger ledger = new SkillMemoryLedger(5, 5);
		SkillMemoryLedger.Reservation reservation = ledger.reserve(1, 1);
		reservation.close();
		for (long charge : new long[]{0, 1}) {
			IllegalStateException failure = assertThrows(IllegalStateException.class,
					() -> reservation.resize(charge, charge));
			assertEquals(IllegalStateException.class, failure.getClass());
			assertFixedFailure(failure);
			assertSnapshot(ledger, 0, 0);
		}
		reservation.close();
		assertSnapshot(ledger, 0, 0);
	}

	@Test
	void closingOneTokenCannotRefundAnotherOrItsLaterReplacement() {
		SkillMemoryLedger ledger = new SkillMemoryLedger(10, 10);
		SkillMemoryLedger.Reservation first = ledger.reserve(3, 4);
		try (SkillMemoryLedger.Reservation second = ledger.reserve(2, 1)) {
			first.close();
			assertSnapshot(ledger, 2, 1);
			try (SkillMemoryLedger.Reservation replacement = ledger.reserve(8, 9)) {
				first.close();
				assertSnapshot(ledger, 10, 10);
			}
			assertSnapshot(ledger, 2, 1);
		}
		assertSnapshot(ledger, 0, 0);
	}

	@Test
	void tryWithResourcesRollsBackAnExceptionalConstruction() {
		SkillMemoryLedger ledger = new SkillMemoryLedger(10, 10);
		RuntimeException sentinel = new RuntimeException("synthetic construction failure");
		try (SkillMemoryLedger.Reservation other = ledger.reserve(2, 3)) {
			assertSame(sentinel, assertThrows(RuntimeException.class, () -> {
				try (SkillMemoryLedger.Reservation pending = ledger.reserve(4, 5)) {
					pending.resize(6, 7);
					throw sentinel;
				}
			}));
			assertSnapshot(ledger, 2, 3);
		}
	}

	@Test
	void snapshotsAreImmutableObservationsAndDoNotRefundLiveCharges() {
		SkillMemoryLedger ledger = new SkillMemoryLedger(1, 1);
		SkillMemoryLedger.Snapshot empty = ledger.snapshot();
		try (SkillMemoryLedger.Reservation reservation = ledger.reserve(1, 1)) {
			SkillMemoryLedger.Snapshot full = ledger.snapshot();
			assertEquals(0, empty.retainedBytes());
			assertEquals(0, empty.transientBytes());
			assertSnapshot(ledger, 1, 1);
			assertLimit(RETAINED, () -> ledger.reserve(1, 0));
			reservation.close();
			assertEquals(1, full.retainedBytes());
			assertEquals(1, full.transientBytes());
		}
		assertSnapshot(ledger, 0, 0);
	}

	@Test
	void maximumLongCeilingsRejectOverflowingReservationsWithoutWrapping() {
		SkillMemoryLedger ledger = new SkillMemoryLedger(Long.MAX_VALUE, Long.MAX_VALUE);
		try (SkillMemoryLedger.Reservation reservation = ledger.reserve(Long.MAX_VALUE, Long.MAX_VALUE)) {
			assertSnapshot(ledger, Long.MAX_VALUE, Long.MAX_VALUE);
			assertLimit(RETAINED, () -> ledger.reserve(1, 0));
			assertLimit(TRANSIENT, () -> ledger.reserve(0, 1));
			try (SkillMemoryLedger.Reservation zero = ledger.reserve(0, 0)) {
				assertSnapshot(ledger, Long.MAX_VALUE, Long.MAX_VALUE);
			}
		}
		assertSnapshot(ledger, 0, 0);
	}

	@Test
	void maximumLongResizeSubtractsItsOwnChargeBeforeCheckingAvailableCapacity() {
		SkillMemoryLedger ledger = new SkillMemoryLedger(Long.MAX_VALUE, Long.MAX_VALUE);
		try (SkillMemoryLedger.Reservation reservation = ledger.reserve(Long.MAX_VALUE - 5, Long.MAX_VALUE - 5);
				SkillMemoryLedger.Reservation other = ledger.reserve(5, 5)) {
			assertLimit(RETAINED, () -> reservation.resize(Long.MAX_VALUE, Long.MAX_VALUE - 5));
			assertLimit(TRANSIENT, () -> reservation.resize(Long.MAX_VALUE - 5, Long.MAX_VALUE));
			assertSnapshot(ledger, Long.MAX_VALUE, Long.MAX_VALUE);
			other.close();
			reservation.resize(Long.MAX_VALUE, Long.MAX_VALUE);
			assertSnapshot(ledger, Long.MAX_VALUE, Long.MAX_VALUE);
			reservation.resize(0, 0);
			assertSnapshot(ledger, 0, 0);
		}
	}

	@Test
	void diagnosticTextIsFixedAndDoesNotContainRequestedAmountsOrCauses() {
		SkillMemoryLedger ledger = new SkillMemoryLedger(1, 1);
		assertEquals(assertLimit(RETAINED, () -> ledger.reserve(2, 0)).getMessage(),
				assertLimit(RETAINED, () -> ledger.reserve(9_876_543, 0)).getMessage());
		assertEquals(assertLimit(TRANSIENT, () -> ledger.reserve(0, 2)).getMessage(),
				assertLimit(TRANSIENT, () -> ledger.reserve(0, 9_876_543)).getMessage());
		assertEquals(assertInvalid(() -> ledger.reserve(-1, 0)).getMessage(),
				assertInvalid(() -> ledger.reserve(-9_876_543, 0)).getMessage());
	}

	@Test
	void sixteenSimultaneousAdmissionsCannotOversubscribeEitherBucket() throws Exception {
		SkillMemoryLedger ledger = new SkillMemoryLedger(4, 4);
		ExecutorService executor = Executors.newFixedThreadPool(16);
		CountDownLatch ready = new CountDownLatch(16), start = new CountDownLatch(1);
		CountDownLatch attempted = new CountDownLatch(16), release = new CountDownLatch(1);
		AtomicInteger admitted = new AtomicInteger();
		List<Future<?>> tasks = new ArrayList<>();
		try {
			for (int index = 0; index < 16; ++index) tasks.add(executor.submit(() -> {
				SkillMemoryLedger.Reservation reservation = null;
				ready.countDown();
				try {
					await(start);
					try {
						reservation = ledger.reserve(1, 1);
						admitted.incrementAndGet();
						SkillMemoryLedger.Snapshot snapshot = ledger.snapshot();
						assertTrue(snapshot.retainedBytes() <= 4);
						assertEquals(snapshot.retainedBytes(), snapshot.transientBytes());
					} catch (SkillMemoryLedger.LimitExceededException expected) {
						assertNotNull(expected.kind());
						assertFixedFailure(expected);
					} finally {
						attempted.countDown();
					}
					await(release);
				} finally {
					if (reservation != null) reservation.close();
				}
				return null;
			}));
			await(ready);
			start.countDown();
			await(attempted);
			assertEquals(4, admitted.get());
			assertSnapshot(ledger, 4, 4);
			release.countDown();
			for (Future<?> task : tasks) task.get(WAIT_SECONDS, SECONDS);
			assertSnapshot(ledger, 0, 0);
		} finally {
			start.countDown();
			release.countDown();
			shutdown(executor);
		}
	}

	@Test
	void concurrentCloseAndResizeEitherCompleteBeforeCloseOrRejectWithoutReopening() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			for (int iteration = 0; iteration < 64; ++iteration) {
				SkillMemoryLedger ledger = new SkillMemoryLedger(10, 10);
				SkillMemoryLedger.Reservation reservation = ledger.reserve(5, 5);
				CountDownLatch start = new CountDownLatch(1);
				Future<?> close = executor.submit(() -> {
					await(start);
					reservation.close();
					reservation.close();
					return null;
				});
				Future<?> resize = executor.submit(() -> {
					await(start);
					try {
						reservation.resize(7, 8);
					} catch (IllegalStateException closed) {
						assertEquals(IllegalStateException.class, closed.getClass());
						assertFixedFailure(closed);
					}
					return null;
				});
				start.countDown();
				close.get(WAIT_SECONDS, SECONDS);
				resize.get(WAIT_SECONDS, SECONDS);
				assertSnapshot(ledger, 0, 0);
				assertThrows(IllegalStateException.class, () -> reservation.resize(1, 1));
			}
		} finally {
			shutdown(executor);
		}
	}

	private static void assertSnapshot(SkillMemoryLedger ledger, long retained, long transientBytes) {
		SkillMemoryLedger.Snapshot snapshot = ledger.snapshot();
		assertEquals(retained, snapshot.retainedBytes());
		assertEquals(transientBytes, snapshot.transientBytes());
	}

	private static SkillMemoryLedger.LimitExceededException assertLimit(
			SkillMemoryLedger.Kind kind, Executable executable) {
		SkillMemoryLedger.LimitExceededException failure =
				assertThrows(SkillMemoryLedger.LimitExceededException.class, executable);
		assertEquals(kind, failure.kind());
		assertFixedFailure(failure);
		return failure;
	}

	private static IllegalArgumentException assertInvalid(Executable executable) {
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, executable);
		assertEquals(IllegalArgumentException.class, failure.getClass());
		assertFixedFailure(failure);
		return failure;
	}

	private static void assertFixedFailure(RuntimeException failure) {
		assertNotNull(failure.getMessage());
		assertFalse(failure.getMessage().isBlank());
		assertNull(failure.getCause());
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		assertTrue(latch.await(WAIT_SECONDS, SECONDS), "Timed out waiting for test coordination.");
	}

	private static void shutdown(ExecutorService executor) throws InterruptedException {
		executor.shutdownNow();
		assertTrue(executor.awaitTermination(WAIT_SECONDS, SECONDS), "Test workers did not terminate.");
	}
}
