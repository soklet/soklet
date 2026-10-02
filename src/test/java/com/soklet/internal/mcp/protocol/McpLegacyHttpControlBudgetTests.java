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
package com.soklet.internal.mcp.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class McpLegacyHttpControlBudgetTests {
	private final AtomicLong now = new AtomicLong();
	private final McpLegacyHttpControlBudget budget = new McpLegacyHttpControlBudget(now::get);
	private final McpEffectivePartition partition = partition("/mcp", "shared");

	@Test
	void simultaneous_capacity_and_attempt_rate_are_independent_and_physical_release_is_required() {
		for (int index = 0; index < 4; index++) assertTrue(budget.reserve(partition));
		for (int index = 4; index < 64; index++) assertFalse(budget.reserve(partition));
		assertEquals(new McpLegacyHttpControlBudget.Snapshot(1, 4, 64), budget.snapshot());
		for (int index = 0; index < 4; index++) budget.release(partition);
		assertFalse(budget.reserve(partition), "Free physical slots do not reset the attempt window.");
		now.set(McpLegacyHttpControlBudget.ATTEMPT_WINDOW_NANOS);
		assertTrue(budget.reserve(partition)); budget.release(partition);
		assertThrows(IllegalStateException.class, () -> budget.release(partition));
	}

	@Test
	void sliding_window_cannot_double_burst_across_a_fixed_second_boundary() {
		now.set(900_000_000L);
		for (int index = 0; index < 64; index++) { assertTrue(budget.reserve(partition)); budget.release(partition); }
		now.set(1_000_000_000L); assertFalse(budget.reserve(partition));
		now.set(1_899_999_999L); assertFalse(budget.reserve(partition));
		now.set(1_900_000_000L); assertTrue(budget.reserve(partition)); budget.release(partition);
		assertEquals(1, budget.snapshot().retainedAttempts());
	}

	@Test
	void retained_partition_cap_rejects_churn_until_a_full_idle_second_and_never_prunes_active_work() {
		List<McpEffectivePartition> partitions = new ArrayList<>();
		for (int index = 0; index < 256; index++) {
			McpEffectivePartition key = partition("/mcp", "owner-" + index); partitions.add(key);
			assertTrue(budget.reserve(key)); if (index != 0) budget.release(key);
		}
		McpEffectivePartition extra = partition("/mcp", "extra");
		assertFalse(budget.reserve(extra)); now.set(999_999_999L); budget.maintain();
		assertEquals(256, budget.snapshot().retainedPartitions()); assertFalse(budget.reserve(extra));
		now.set(1_000_000_000L); budget.maintain();
		assertEquals(new McpLegacyHttpControlBudget.Snapshot(1, 1, 0), budget.snapshot());
		assertTrue(budget.reserve(extra)); budget.release(extra);
		now.set(10_000_000_000L); budget.maintain(); assertEquals(1, budget.snapshot().retainedPartitions());
		budget.release(partitions.get(0)); budget.maintain(); assertEquals(1, budget.snapshot().retainedPartitions());
		now.addAndGet(1_000_000_000L); budget.maintain();
		assertEquals(new McpLegacyHttpControlBudget.Snapshot(0, 0, 0), budget.snapshot());
	}

	@Test
	void endpoint_and_partition_keys_are_separate_without_exposing_them_in_diagnostics() {
		McpEffectivePartition otherEndpoint = partition("/other", "shared");
		McpEffectivePartition otherOwner = partition("/mcp", "other");
		for (int index = 0; index < 4; index++) assertTrue(budget.reserve(partition));
		assertTrue(budget.reserve(otherEndpoint)); assertTrue(budget.reserve(otherOwner));
		assertEquals(3, budget.snapshot().retainedPartitions());
		assertFalse(budget.snapshot().toString().contains("shared"));
		for (int index = 0; index < 4; index++) budget.release(partition);
		budget.release(otherEndpoint); budget.release(otherOwner);
	}

	@Test
	void simultaneous_reservation_race_stays_within_four_slots_and_releases_exactly_once() throws Exception {
		CountDownLatch begin = new CountDownLatch(1); CountDownLatch attempted = new CountDownLatch(32);
		CountDownLatch release = new CountDownLatch(1); AtomicInteger accepted = new AtomicInteger();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		List<Thread> threads = new ArrayList<>();
		try {
			for (int index = 0; index < 32; index++) {
				Thread thread = new Thread(() -> {
					boolean reserved = false;
					try {
						assertTrue(begin.await(5, TimeUnit.SECONDS));
						reserved = budget.reserve(partition); if (reserved) accepted.incrementAndGet();
						attempted.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
					} catch (Throwable throwable) { failure.compareAndSet(null, throwable); }
					finally { if (reserved) budget.release(partition); }
				}, "mcp-legacy-control-race-" + index);
				threads.add(thread); thread.start();
			}
			begin.countDown(); assertTrue(attempted.await(5, TimeUnit.SECONDS));
			assertEquals(4, accepted.get()); assertEquals(new McpLegacyHttpControlBudget.Snapshot(1, 4, 32), budget.snapshot());
		} finally {
			begin.countDown(); release.countDown();
			for (Thread thread : threads) { thread.join(5000); assertFalse(thread.isAlive()); }
		}
		assertEquals(0, budget.snapshot().activeReservations());
		assertNull(failure.get());
	}

	@Test
	void monotonic_wrap_preserves_the_sliding_window_and_idle_pruning() {
		now.set(Long.MAX_VALUE - 100_000_000L);
		for (int index = 0; index < 64; index++) { assertTrue(budget.reserve(partition)); budget.release(partition); }
		now.addAndGet(999_999_999L); assertFalse(budget.reserve(partition));
		now.incrementAndGet(); assertTrue(budget.reserve(partition)); budget.release(partition);
		now.addAndGet(1_000_000_000L); budget.maintain();
		assertEquals(new McpLegacyHttpControlBudget.Snapshot(0, 0, 0), budget.snapshot());
	}

	private static McpEffectivePartition partition(String endpoint, String key) {
		return new McpEffectivePartition(new McpEndpointPartitionIdentity(endpoint),
				McpPartitionPurpose.AUTHORIZATION, Optional.of(key));
	}
}
