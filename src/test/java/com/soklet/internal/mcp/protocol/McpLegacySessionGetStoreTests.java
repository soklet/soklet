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

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class McpLegacySessionGetStoreTests {
	private static final long SECOND = 1_000_000_000L;
	private final AtomicLong now = new AtomicLong();
	private final AtomicInteger tokens = new AtomicInteger();
	private final Object generation = new Object();
	private final McpLegacySessionStore.Owner owner = new McpLegacySessionStore.Owner("owner", false);
	private final McpLegacySessionStore.Snapshot snapshot = new McpLegacySessionStore.Snapshot(
			McpClientCapabilities.empty(), Optional.empty());
	private final McpEffectivePartition partition = partition("shared");

	@Test
	void replacement_keeps_two_logical_gets_one_quota_and_all_old_physical_evidence() {
		for (String revision : List.of("2025-06-18", "2025-11-25")) {
			Quota quota = new Quota(1); McpLegacySessionStore store = store(quota);
			McpLegacySessionStore.Initialization initialization = initialize(store, revision);
			initialization.physicalComplete();
			GetTarget firstTarget = new GetTarget(); GetTarget secondTarget = new GetTarget();
			McpLegacySessionStore.Get first = get(store, initialization, revision, firstTarget, 64, 60 * SECOND, 120 * SECOND);
			McpLegacySessionStore.Get second = get(store, initialization, revision, secondTarget, 64, 60 * SECOND, 120 * SECOND);
			long before = store.counts().retainedBytes();
			McpLegacySessionStore.Get newest = get(store, initialization, revision, new GetTarget(), 64, 60 * SECOND, 120 * SECOND);
			assertEquals(new McpLegacySessionStore.GetCounts(2, 3, 1), store.getCounts());
			assertEquals(McpLegacySessionStore.GetCause.REPLACED, firstTarget.cause.get());
			assertEquals(1, firstTarget.retirements.get()); assertEquals(0, secondTarget.retirements.get());
			assertFalse(first.active()); assertTrue(second.active()); assertTrue(newest.active());
			assertSame(snapshot, newest.snapshot()); assertEquals("Get[redacted]", newest.toString());
			assertEquals(before + 64, store.counts().retainedBytes());
			assertEquals(1, quota.reservations.get()); assertEquals(1, quota.active(partition));
			first.physicalComplete(); assertEquals(before, store.counts().retainedBytes());
			second.physicalComplete(); newest.physicalComplete();
			assertEquals(new McpLegacySessionStore.GetCounts(0, 0, 0), store.getCounts());
			assertEquals(1, quota.releases.get());
			store.close(); assertEquals(new McpLegacySessionStore.Counts(0, 0, 0, 0, 0), store.counts());
		}
	}

	@Test
	void rejected_replacement_preserves_both_current_gets_and_does_not_move_the_partition() {
		Quota quota = new Quota(1);
		McpLegacySessionStore store = store(quota, 256, 512, 1024, 120 * SECOND, 300 * SECOND);
		McpLegacySessionStore.Initialization initialization = initialize(store, "2025-11-25"); initialization.physicalComplete();
		GetTarget firstTarget = new GetTarget(); GetTarget secondTarget = new GetTarget();
		McpLegacySessionStore.Get first = get(store, initialization, "2025-11-25", firstTarget, 64, 60 * SECOND, 120 * SECOND);
		McpLegacySessionStore.Get second = get(store, initialization, "2025-11-25", secondTarget, 64, 60 * SECOND, 120 * SECOND);
		long bytes = store.counts().retainedBytes();
		assertEquals(McpLegacySessionStore.Status.OWNER_CAPACITY, store.reserveGet(initialization.sessionId(), owner,
				"/mcp", "2025-11-25", generation, partition, new GetTarget(), 100, 60 * SECOND, 120 * SECOND).status());
		assertEquals(McpLegacySessionStore.Status.PARTITION_MISMATCH, store.reserveGet(initialization.sessionId(), owner,
				"/mcp", "2025-11-25", generation, partition("other"), new GetTarget(), 1, 60 * SECOND, 120 * SECOND).status());
		assertEquals(McpLegacySessionStore.Status.AUTHORIZATION_EXPIRED, store.reserveGet(initialization.sessionId(), owner,
				"/mcp", "2025-11-25", generation, partition, new GetTarget(), 1, 0, 120 * SECOND).status());
		assertEquals(bytes, store.counts().retainedBytes()); assertTrue(first.active()); assertTrue(second.active());
		assertEquals(0, firstTarget.retirements.get()); assertEquals(0, secondTarget.retirements.get());
		assertEquals(new McpLegacySessionStore.GetCounts(2, 2, 1), store.getCounts());
		first.physicalComplete(); second.physicalComplete(); store.close();
		assertEquals(0, store.counts().retainedBytes());
	}

	@Test
	void first_get_uses_the_shared_quota_and_fixed_partition_survives_a_get_gap() {
		Quota quota = new Quota(1); quota.active.put(partition, 1); // existing modern subscription
		McpLegacySessionStore store = store(quota);
		McpLegacySessionStore.Initialization initialization = initialize(store, "2025-06-18"); initialization.physicalComplete();
		long bytes = store.counts().retainedBytes();
		assertEquals(McpLegacySessionStore.Status.GLOBAL_CAPACITY, store.reserveGet(initialization.sessionId(), owner,
				"/mcp", "2025-06-18", generation, partition, new GetTarget(), 1, 60 * SECOND, 120 * SECOND).status());
		assertEquals(bytes, store.counts().retainedBytes()); assertEquals(new McpLegacySessionStore.GetCounts(0, 0, 0), store.getCounts());
		quota.active.remove(partition);
		McpLegacySessionStore.Get first = get(store, initialization, "2025-06-18", new GetTarget(), 1, 60 * SECOND, 120 * SECOND);
		first.physicalComplete();
		assertEquals(0, quota.active(partition));
		assertEquals(McpLegacySessionStore.Status.PARTITION_MISMATCH, store.reserveGet(initialization.sessionId(), owner,
				"/mcp", "2025-06-18", generation, partition("new-partition"), new GetTarget(), 1, 60 * SECOND, 120 * SECOND).status());
		McpLegacySessionStore.Get reconnected = get(store, initialization, "2025-06-18", new GetTarget(), 1, 60 * SECOND, 120 * SECOND);
		assertEquals(1, quota.active(partition)); reconnected.physicalComplete(); store.close();
		assertEquals(2, quota.releases.get()); assertEquals(0, store.counts().retainedBytes());
	}

	@Test
	void lease_expiry_closes_only_the_get_and_physical_exit_starts_session_quiescence() {
		Quota quota = new Quota(1);
		McpLegacySessionStore store = store(quota, 1024, 2048, 4096, 10 * SECOND, 100 * SECOND);
		McpLegacySessionStore.Initialization initialization = initialize(store, "2025-06-18"); initialization.physicalComplete();
		GetTarget target = new GetTarget();
		McpLegacySessionStore.Get get = get(store, initialization, "2025-06-18", target, 64, 10 * SECOND, 90 * SECOND);
		long generation = get.generation(); now.set(10 * SECOND); store.maintain();
		assertEquals(McpLegacySessionStore.GetCause.LEASE_EXPIRED, target.cause.get()); assertFalse(get.active());
		assertFalse(get.renew(generation, owner, partition, 80 * SECOND));
		assertEquals(new McpLegacySessionStore.GetCounts(0, 1, 0), store.getCounts());
		assertEquals(1, store.counts().liveSessions()); assertEquals(0, quota.active(partition));
		now.set(50 * SECOND); store.maintain(); assertEquals(1, store.counts().liveSessions());
		get.physicalComplete(); now.set(59 * SECOND); store.maintain(); assertEquals(1, store.counts().liveSessions());
		now.set(60 * SECOND); store.maintain(); assertEquals(0, store.counts().retainedBytes());
	}

	@Test
	void reconciliation_generation_and_original_total_deadline_cannot_be_bypassed_by_renewal() {
		Quota quota = new Quota(1); McpLegacySessionStore store = store(quota);
		McpLegacySessionStore.Initialization initialization = initialize(store, "2025-11-25"); initialization.physicalComplete();
		GetTarget target = new GetTarget();
		McpLegacySessionStore.Get get = get(store, initialization, "2025-11-25", target, 1, 10 * SECOND, 20 * SECOND);
		long beforeFence = get.generation(); now.set(5 * SECOND); store.fenceGets();
		assertFalse(get.active()); assertEquals(get.generation(), target.fencedGeneration.get());
		assertFalse(get.renew(beforeFence, owner, partition, 100 * SECOND));
		assertTrue(get.renew(get.generation(), owner, partition, 100 * SECOND));
		assertEquals(20 * SECOND, get.deadlineNanos()); assertEquals(20 * SECOND, get.totalDeadlineNanos());
		now.set(19 * SECOND); assertTrue(get.renew(get.generation(), owner, partition, 200 * SECOND));
		assertEquals(20 * SECOND, get.deadlineNanos());
		now.set(20 * SECOND); assertFalse(get.active()); store.maintain();
		assertEquals(McpLegacySessionStore.GetCause.TOTAL_LIFETIME_EXPIRED, target.cause.get());
		assertFalse(get.renew(get.generation(), owner, partition, 200 * SECOND));
		get.physicalComplete(); store.close(); assertEquals(0, store.counts().retainedBytes());
	}

	@Test
	void changed_renewal_owner_closes_one_get_without_migrating_or_retiring_the_session() {
		Quota quota = new Quota(1); McpLegacySessionStore store = store(quota);
		McpLegacySessionStore.Initialization initialization = initialize(store, "2025-06-18"); initialization.physicalComplete();
		GetTarget firstTarget = new GetTarget();
		McpLegacySessionStore.Get first = get(store, initialization, "2025-06-18", firstTarget, 1, 60 * SECOND, 120 * SECOND);
		McpLegacySessionStore.Get second = get(store, initialization, "2025-06-18", new GetTarget(), 1, 60 * SECOND, 120 * SECOND);
		assertFalse(first.renew(first.generation(), new McpLegacySessionStore.Owner("new-owner", false), partition, 80 * SECOND));
		assertEquals(McpLegacySessionStore.GetCause.AUTHORIZATION_DENIED, firstTarget.cause.get());
		assertTrue(second.active()); assertEquals(1, store.counts().liveSessions()); assertEquals(1, quota.active(partition));
		assertEquals(new McpLegacySessionStore.GetCounts(1, 2, 1), store.getCounts());
		first.physicalComplete(); second.physicalComplete(); store.close(); assertEquals(0, store.counts().retainedBytes());
	}

	@Test
	void delete_rechecks_current_decision_atomically_and_retires_other_work_without_releasing_physical_evidence() {
		for (String revision : List.of("2025-06-18", "2025-11-25")) {
			Quota quota = new Quota(1); McpLegacySessionStore store = store(quota);
			McpLegacySessionStore.Initialization initialization = initialize(store, revision); initialization.physicalComplete();
			AtomicReference<McpLegacySessionStore.Cause> callCause = new AtomicReference<>();
			McpLegacySessionStore.Call active = store.acquire(initialization.sessionId(), owner, "/mcp", revision,
					generation, new McpJsonRpcId.StringId("active"), null, target(callCause), 64).call().orElseThrow();
			assertTrue(active.acceptedUse());
			GetTarget getTarget = new GetTarget();
			McpLegacySessionStore.Get get = get(store, initialization, revision, getTarget, 64, 60 * SECOND, 120 * SECOND);
			McpLegacySessionStore.Call delete = store.acquire(initialization.sessionId(), owner, "/mcp", revision,
					generation, null, null, target(new AtomicReference<>()), 16).call().orElseThrow();
			assertTrue(delete.acceptedUse());
			assertFalse(store.retireIfCurrent(delete, McpLegacySessionStore.Cause.SESSION_CLOSED, now.get()));
			assertTrue(get.active()); assertEquals(1, store.counts().liveSessions());
			assertTrue(store.retireIfCurrent(delete, McpLegacySessionStore.Cause.SESSION_CLOSED, now.get() + SECOND));
			assertEquals(McpLegacySessionStore.Cause.SESSION_CLOSED, callCause.get());
			assertEquals(McpLegacySessionStore.GetCause.SESSION_CLOSED, getTarget.cause.get());
			assertEquals(new McpLegacySessionStore.GetCounts(0, 1, 0), store.getCounts());
			assertTrue(store.counts().retainedBytes() > 0); assertEquals(3, store.counts().physicalReferences());
			assertFalse(store.retireIfCurrent(delete, McpLegacySessionStore.Cause.SESSION_CLOSED, now.get() + SECOND));
			active.physicalComplete(); delete.physicalComplete(); get.physicalComplete();
			assertEquals(new McpLegacySessionStore.Counts(0, 0, 0, 0, 0), store.counts());
		}
	}

	@Test
	void quota_and_target_callbacks_can_reenter_and_one_bad_fence_does_not_skip_another() {
		Quota quota = new Quota(1); McpLegacySessionStore store = store(quota);
		McpLegacySessionStore.Initialization initialization = initialize(store, "2025-11-25"); initialization.physicalComplete();
		AtomicBoolean reentered = new AtomicBoolean();
		GetTarget good = new GetTarget() {
			@Override public void fence(long generation) {
				super.fence(generation);
				Thread other = new Thread(store::getCounts);
				other.start();
				try { other.join(1000); reentered.set(!other.isAlive()); }
				catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
			}
		};
		McpLegacySessionStore.Get first = get(store, initialization, "2025-11-25", new GetTarget() {
			@Override public void fence(long generation) { throw new IllegalStateException("contained"); }
		}, 1, 60 * SECOND, 120 * SECOND);
		McpLegacySessionStore.Get second = get(store, initialization, "2025-11-25", good, 1, 60 * SECOND, 120 * SECOND);
		store.fenceGets(); assertFalse(first.active()); assertFalse(second.active());
		assertTrue(reentered.get(), "GET fencing callbacks must run outside the store lock.");
		assertEquals(second.generation(), good.fencedGeneration.get());
		assertDoesNotThrow(store::close); first.physicalComplete(); second.physicalComplete();
		assertEquals(0, store.counts().retainedBytes()); assertEquals(1, quota.releases.get());
	}

	@Test
	void lookup_bindings_are_neutral_and_session_hard_expiry_caps_every_get() {
		Quota quota = new Quota(1);
		McpLegacySessionStore store = store(quota, 1024, 2048, 4096, 10 * SECOND, 20 * SECOND);
		McpLegacySessionStore.Initialization initialization = initialize(store, "2025-06-18"); initialization.physicalComplete();
		for (McpLegacySessionStore.Owner wrong : List.of(new McpLegacySessionStore.Owner("other", false),
				new McpLegacySessionStore.Owner("owner", true)))
			assertEquals(McpLegacySessionStore.Status.NOT_FOUND, store.reserveGet(initialization.sessionId(), wrong,
					"/mcp", "2025-06-18", generation, partition, new GetTarget(), 1, 60 * SECOND, 120 * SECOND).status());
		assertEquals(McpLegacySessionStore.Status.NOT_FOUND, store.reserveGet(initialization.sessionId(), owner,
				"/other", "2025-06-18", generation, partition, new GetTarget(), 1, 60 * SECOND, 120 * SECOND).status());
		assertEquals(McpLegacySessionStore.Status.NOT_FOUND, store.reserveGet(initialization.sessionId(), owner,
				"/mcp", "2025-06-18", new Object(), partition, new GetTarget(), 1, 60 * SECOND, 120 * SECOND).status());
		assertEquals(McpLegacySessionStore.Status.REVISION_MISMATCH, store.reserveGet(initialization.sessionId(), owner,
				"/mcp", "2025-11-25", generation, partition, new GetTarget(), 1, 60 * SECOND, 120 * SECOND).status());
		GetTarget target = new GetTarget();
		McpLegacySessionStore.Get get = get(store, initialization, "2025-06-18", target, 1, 60 * SECOND, 120 * SECOND);
		assertEquals(20 * SECOND, get.deadlineNanos()); assertEquals(20 * SECOND, get.totalDeadlineNanos());
		now.set(20 * SECOND); store.maintain();
		assertEquals(McpLegacySessionStore.GetCause.SESSION_EXPIRED, target.cause.get());
		assertEquals(0, store.counts().liveSessions()); get.physicalComplete(); assertEquals(0, store.counts().retainedBytes());
	}

	private McpLegacySessionStore store(Quota quota) { return store(quota, 1024, 2048, 4096, 120 * SECOND, 300 * SECOND); }
	private McpLegacySessionStore store(Quota quota, long sessionBytes, long ownerBytes, long globalBytes, long idle, long hard) {
		McpLegacySessionStore store = new McpLegacySessionStore(new McpLegacySessionStore.Config(4, 4, idle, hard,
				65536, false, sessionBytes, ownerBytes, globalBytes), McpJsonLimits.productionDefaults(), now::get, () -> {
			byte[] bytes = new byte[32]; ByteBuffer.wrap(bytes).putInt(tokens.incrementAndGet()); return bytes;
		});
		store.configureGetQuota(quota); return store;
	}
	private McpLegacySessionStore.Initialization initialize(McpLegacySessionStore store, String revision) {
		return store.publish(owner, "/mcp", revision, generation, snapshot, target(new AtomicReference<>())).initialization().orElseThrow();
	}
	private McpLegacySessionStore.Get get(McpLegacySessionStore store, McpLegacySessionStore.Initialization initialization,
			String revision, GetTarget target, long bytes, long lease, long total) {
		return store.reserveGet(initialization.sessionId(), owner, "/mcp", revision, generation, partition, target,
				bytes, lease, total).get().orElseThrow();
	}
	private static McpEffectivePartition partition(String key) {
		return new McpEffectivePartition(new McpEndpointPartitionIdentity("/mcp"), McpPartitionPurpose.AUTHORIZATION, Optional.of(key));
	}
	private static McpLegacySessionStore.Target target(AtomicReference<McpLegacySessionStore.Cause> reason) {
		return new McpLegacySessionStore.Target() {
			@Override public boolean cancel(McpLegacySessionStore.Cause cause) { reason.set(cause); return true; }
			@Override public void retire(McpLegacySessionStore.Cause cause) { reason.set(cause); }
		};
	}
	private static class GetTarget implements McpLegacySessionStore.GetTarget {
		final AtomicInteger retirements = new AtomicInteger();
		final AtomicReference<McpLegacySessionStore.GetCause> cause = new AtomicReference<>();
		final AtomicLong fencedGeneration = new AtomicLong();
		@Override public void retire(McpLegacySessionStore.GetCause reason) { cause.set(reason); retirements.incrementAndGet(); }
		@Override public void fence(long generation) { fencedGeneration.set(generation); }
	}
	private static final class Quota implements McpLegacySessionStore.GetQuota {
		final Map<McpEffectivePartition, Integer> active = new HashMap<>();
		final AtomicInteger reservations = new AtomicInteger();
		final AtomicInteger releases = new AtomicInteger();
		final int limit;
		Quota(int limit) { this.limit = limit; }
		int active(McpEffectivePartition partition) { return active.getOrDefault(partition, 0); }
		@Override public boolean reserve(McpEffectivePartition partition) {
			if (active(partition) >= limit) return false;
			active.put(partition, active(partition) + 1); reservations.incrementAndGet(); return true;
		}
		@Override public void release(McpEffectivePartition partition) {
			int count = active(partition); assertTrue(count > 0);
			if (count == 1) active.remove(partition); else active.put(partition, count - 1);
			releases.incrementAndGet();
		}
	}
}
