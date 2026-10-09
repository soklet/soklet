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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class McpLegacySessionGrantStoreTests {
	private static final long SECOND = 1_000_000_000L;
	private static final String REVISION = "2025-11-25";
	private static final McpLegacySessionStore.Target REQUEST_TARGET = new McpLegacySessionStore.Target() {
		@Override public boolean cancel(McpLegacySessionStore.Cause cause) { return false; }
		@Override public void retire(McpLegacySessionStore.Cause cause) {}
	};

	@Test
	void backgroundRenewalDoesNotResetClientIdleTimeOrPreventLogicalExpiry() {
		for (String revision : List.of("2025-06-18", "2025-11-25")) {
			for (boolean unfinishedRenewal : List.of(false, true)) {
				Fixture fixture = new Fixture(revision);
				Session session = fixture.session("owner");
				McpLegacySessionStore.Grant grant = fixture.subscribe(session, "test:///idle", new GrantTarget(),
						64, 120 * SECOND, 600 * SECOND);
				for (int tick : List.of(90, 180, 270)) {
					fixture.now.set(tick * SECOND);
					long generation = grant.generation();
					try (McpLegacySessionStore.GrantWork work = grant.acquireWork(generation).orElseThrow()) {
						assertTrue(work.retainedEvidenceBytes() > 0);
						assertTrue(grant.renew(generation, fixture.now.get() + 120 * SECOND));
					}
				}
				fixture.now.set(299 * SECOND);
				fixture.store.maintain();
				assertEquals(1, fixture.store.counts().liveSessions());
				McpLegacySessionStore.GrantWork blockedWork = unfinishedRenewal
						? grant.acquireWork(grant.generation()).orElseThrow() : null;
				fixture.now.set(300 * SECOND);
				fixture.store.maintain();
				assertEquals(0, fixture.store.counts().liveSessions(), revision + ": background work is not client activity");
				assertFalse(grant.current());
				assertEquals(McpLegacySessionStore.Status.NOT_FOUND, fixture.store.acquire(session.id, session.owner,
						"/mcp", revision, fixture.generation, null, null, REQUEST_TARGET, 0).status());
				if (blockedWork != null) {
					assertEquals(1, fixture.store.counts().physicalReferences());
					assertTrue(fixture.store.counts().retainedBytes() > 0, "The unfinished callback still owns its evidence.");
					assertFalse(grant.renew(grant.generation(), 500 * SECOND));
					blockedWork.close();
					blockedWork.close();
				}
				fixture.assertEmpty();
			}
		}
	}

	@Test
	void sameOwnerCanEvictAnIdleSessionWhileRenewalEvidenceRemainsPhysicallyOwned() {
		for (String revision : List.of("2025-06-18", "2025-11-25")) {
			Fixture fixture = new Fixture(revision);
			List<McpLegacySessionStore.Grant> grants = new ArrayList<>();
			List<McpLegacySessionStore.GrantWork> work = new ArrayList<>();
			for (int index = 0; index < 4; index++) {
				Session session = fixture.session("owner");
				McpLegacySessionStore.Grant grant = fixture.subscribe(session, "test:///idle-" + index,
						new GrantTarget(), 64, 120 * SECOND, 600 * SECOND);
				grants.add(grant);
				work.add(grant.acquireWork(grant.generation()).orElseThrow());
			}
			fixture.now.set(31 * SECOND);
			McpLegacySessionStore.Allocation allocation = fixture.store.publish(
					new McpLegacySessionStore.Owner("owner", false), "/mcp", revision, fixture.generation,
					new McpLegacySessionStore.Snapshot(McpClientCapabilities.empty(), Optional.empty()), REQUEST_TARGET);
			assertEquals(McpLegacySessionStore.Status.ACCEPTED, allocation.status());
			assertFalse(grants.get(0).current(), "The oldest idle session is retired even while renewal is unfinished.");
			for (McpLegacySessionStore.Grant grant : grants.subList(1, grants.size()))
				assertTrue(grant.current());
			assertEquals(4, fixture.store.counts().liveSessions());
			assertEquals(5, fixture.store.counts().physicalReferences(), "All four unfinished callbacks retain physical ownership.");
			fixture.store.close();
			allocation.initialization().orElseThrow().physicalComplete();
			assertEquals(4, fixture.store.counts().physicalReferences());
			work.forEach(McpLegacySessionStore.GrantWork::close);
			fixture.assertEmpty();
		}
	}

	@Test
	void persistentGrantEvidenceCannotBlockUnsubscribeOrSessionDeletion() {
		for (String revision : List.of("2025-06-18", "2025-11-25")) {
			Fixture fixture = new Fixture(revision);
			Session session = fixture.session("owner");
			List<McpLegacySessionStore.Grant> grants = new ArrayList<>();
			for (int index = 0; index < 63; index++)
				grants.add(fixture.subscribe(session, "test:///resource/" + index, new GrantTarget(),
						16_384, 60 * SECOND, 120 * SECOND));
			assertTrue(fixture.store.counts().retainedBytes() > 1_000_000);
			McpLegacySessionStore.Grant first = grants.get(0);
			McpLegacySessionStore.GrantWork held = first.acquireWork(first.generation()).orElseThrow();
			McpLegacySessionStore.Call candidate = fixture.call(session, 32_768);
			long beforePromotion = fixture.store.counts().retainedBytes();
			assertEquals(McpLegacySessionStore.Status.OWNER_CAPACITY, fixture.store.beginGrant(candidate,
					"test:///overflow", fixture.partition, new GrantTarget(), 120 * SECOND).status());
			assertEquals(beforePromotion, fixture.store.counts().retainedBytes());
			candidate.physicalComplete();
			assertEquals(beforePromotion - 32_768, fixture.store.counts().retainedBytes());
			McpLegacySessionStore.Call unsubscribe = fixture.call(session, 32_768);
			assertTrue(fixture.store.unsubscribe(unsubscribe, first.uri()));
			unsubscribe.physicalComplete();
			assertFalse(first.current());
			McpLegacySessionStore.Call delete = fixture.call(session, 32_768);
			assertTrue(fixture.store.retireIfCurrent(delete, McpLegacySessionStore.Cause.SESSION_CLOSED, 60 * SECOND));
			delete.physicalComplete();
			assertEquals(0, fixture.store.counts().liveSessions());
			assertEquals(1, fixture.store.counts().physicalReferences());
			assertTrue(fixture.store.counts().retainedBytes() >= 16_384,
					"The unfinished callback retains its full historical request.");
			assertFalse(first.renew(first.generation(), 60 * SECOND));
			held.close(); held.close();
			fixture.store.close(); fixture.assertEmpty();
		}
	}

	@Test
	void grant_pins_real_subscribe_evidence_after_post_and_after_logical_revocation_until_callback_exit() {
		Fixture fixture = new Fixture(); Session session = fixture.session("owner");
		McpLegacySessionStore.Call subscribe = fixture.call(session, 1000);
		McpLegacySessionStore.Grant grant = fixture.begin(subscribe, "test:///resource", new GrantTarget(), 120 * SECOND);
		assertTrue(grant.commit(grant.generation(), 60 * SECOND));
		subscribe.physicalComplete(); grant.physicalComplete();
		long activeBytes = fixture.store.counts().retainedBytes();
		assertEquals(1000, grant.retainedEvidenceBytes());
		assertEquals(0, fixture.store.counts().physicalReferences());
		McpLegacySessionStore.GrantWork renewal = grant.acquireWork(grant.generation()).orElseThrow();
		McpLegacySessionStore.Call unsubscribe = fixture.call(session, 0);
		assertTrue(fixture.store.unsubscribe(unsubscribe, "test:///resource")); unsubscribe.physicalComplete();
		assertFalse(grant.current()); assertEquals(0, fixture.store.grantCounts().logicalGrants());
		assertEquals(activeBytes, fixture.store.counts().retainedBytes());
		assertEquals("test:///resource".length(), fixture.store.grantCounts().retainedUriBytes());
		fixture.store.close(); assertTrue(fixture.store.counts().retainedBytes() > 1000);
		renewal.close(); renewal.close(); grant.physicalComplete();
		fixture.assertEmpty();
	}

	@Test
	void duplicate_replaces_original_evidence_atomically_preserves_total_lifetime_and_fences_old_results() {
		Fixture fixture = new Fixture(); Session session = fixture.session("owner"); GrantTarget firstTarget = new GrantTarget();
		McpLegacySessionStore.Grant first = fixture.subscribe(session, "test:///resource", firstTarget, 100, 60 * SECOND, 120 * SECOND);
		long firstGeneration = first.generation();
		McpLegacySessionStore.GrantWork oldRenewal = first.acquireWork(firstGeneration).orElseThrow();
		fixture.now.set(10 * SECOND);
		McpLegacySessionStore.Call secondCall = fixture.call(session, 200); GrantTarget secondTarget = new GrantTarget();
		McpLegacySessionStore.Grant second = fixture.begin(secondCall, "TEST:///resource", secondTarget, 240 * SECOND);
		assertTrue(first.active()); assertTrue(first.isFenced()); assertFalse(second.active()); assertTrue(second.current());
		assertFalse(first.tryBeginAuthorization());
		assertEquals(120 * SECOND, second.totalDeadlineNanos());
		assertFalse(first.renew(firstGeneration, 100 * SECOND));
		assertTrue(second.commit(second.generation(), 100 * SECOND));
		secondCall.physicalComplete(); second.physicalComplete();
		assertFalse(first.current()); assertEquals(McpLegacySessionStore.GrantCause.REPLACED, firstTarget.cause.get());
		assertEquals(1, firstTarget.retirements.get());
		first.retire(McpLegacySessionStore.GrantCause.AUTHORIZATION_DENIED);
		assertTrue(second.active()); assertEquals(1, fixture.store.grantCounts().logicalGrants());
		oldRenewal.close(); fixture.store.close(); fixture.assertEmpty();
	}

	@Test
	void unsubscribe_fences_pending_establishment_and_is_neutral_for_an_absent_uri() {
		Fixture fixture = new Fixture(); Session session = fixture.session("owner");
		McpLegacySessionStore.Call subscribe = fixture.call(session, 16);
		GrantTarget target = new GrantTarget(); McpLegacySessionStore.Grant grant = fixture.begin(subscribe,
				"test:///resource", target, 120 * SECOND); long generation = grant.generation();
		McpLegacySessionStore.Call unsubscribe = fixture.call(session, 0);
		assertTrue(fixture.store.unsubscribe(unsubscribe, "TEST:///resource"));
		assertTrue(fixture.store.unsubscribe(unsubscribe, "test:///missing"));
		assertFalse(grant.current()); assertFalse(grant.commit(generation, 60 * SECOND));
		assertEquals(McpLegacySessionStore.GrantCause.UNSUBSCRIBED, target.cause.get());
		assertEquals(1, target.retirements.get());
		subscribe.physicalComplete(); unsubscribe.physicalComplete(); grant.physicalComplete();
		fixture.store.close(); fixture.assertEmpty();
	}

	@Test
	void reconciliation_includes_detached_and_pending_grants_and_stale_denial_cannot_overtake_renewal() {
		Fixture fixture = new Fixture(); Session session = fixture.session("owner"); GrantTarget target = new GrantTarget();
		McpLegacySessionStore.Grant active = fixture.subscribe(session, "test:///active", target, 1, 60 * SECOND, 120 * SECOND);
		McpLegacySessionStore.Call call = fixture.call(session, 1);
		GrantTarget pendingTarget = new GrantTarget(); McpLegacySessionStore.Grant pending = fixture.begin(call,
				"test:///pending", pendingTarget, 120 * SECOND);
		long pendingGeneration = pending.generation(); fixture.store.fenceGrants();
		assertTrue(active.isFenced()); assertTrue(active.active()); assertEquals(1, target.fences.get());
		assertEquals(1, pendingTarget.fences.get()); assertFalse(pending.commit(pendingGeneration, 60 * SECOND));
		assertTrue(pending.current()); assertTrue(pending.commit(pending.generation(), 60 * SECOND));
		long staleDenialGeneration = active.generation(); assertTrue(active.renew(staleDenialGeneration, 70 * SECOND));
		assertFalse(active.retireIfCurrent(staleDenialGeneration, McpLegacySessionStore.GrantCause.AUTHORIZATION_DENIED));
		assertTrue(active.active()); assertEquals(2, fixture.store.pendingGrants("/mcp", REVISION).size());
		call.physicalComplete(); pending.physicalComplete(); fixture.store.close(); fixture.assertEmpty();
	}

	@Test
	void initial_capacity_abort_cannot_complete_late_and_a_duplicate_abort_preserves_old_bounded_grant() {
		Fixture fixture = new Fixture(); Session session = fixture.session("owner");
		McpLegacySessionStore.Grant old = fixture.subscribe(session, "test:///resource", new GrantTarget(), 16, 60 * SECOND, 120 * SECOND);
		McpLegacySessionStore.Call call = fixture.call(session, 16);
		McpLegacySessionStore.Grant pending = fixture.begin(call, "test:///resource", new GrantTarget(), 240 * SECOND);
		assertEquals(McpLegacySessionStore.Status.GLOBAL_CAPACITY, pending.commitStatus(pending.generation(), SECOND / 100));
		long generation = pending.generation(); pending.abort();
		assertFalse(pending.commit(generation, 60 * SECOND)); assertTrue(old.active()); assertTrue(old.isFenced());
		assertEquals(List.of(old), fixture.store.pendingGrants("/mcp", REVISION));
		assertTrue(old.renew(old.generation(), 60 * SECOND));
		call.physicalComplete(); pending.physicalComplete(); fixture.store.close(); fixture.assertEmpty();
	}

	@Test
	void uri_equality_coalesces_scheme_and_escape_case_for_updates_and_unsubscribe() {
		Fixture fixture = new Fixture(); Session session = fixture.session("owner");
		McpLegacySessionStore.Grant grant = fixture.subscribe(session, "TEST:///r%2fpart", new GrantTarget(), 1, 60 * SECOND, 120 * SECOND);
		McpLegacySessionStore.Get get = fixture.get(session, Set.of(McpResourceNotificationType.RESOURCE_UPDATED), 60 * SECOND);
		fixture.store.markResourceDirty("/mcp", REVISION, "test:///r%2Fpart");
		assertEquals(1, fixture.store.pendingDeliveries("/mcp", REVISION).size());
		McpLegacySessionStore.Call call = fixture.call(session, 0);
		assertTrue(fixture.store.unsubscribe(call, "test:///r%2Fpart")); assertFalse(grant.active());
		assertTrue(fixture.store.pendingDeliveries("/mcp", REVISION).isEmpty());
		call.physicalComplete(); get.physicalComplete(); fixture.store.close(); fixture.assertEmpty();
	}

	@Test
	void uri_limits_are_per_owner_global_and_retained_bytes_survive_retired_ignored_callbacks() {
		Fixture fixture = new Fixture(); Session session = fixture.session("owner");
		for (int index = 0; index < 64; index++) fixture.subscribe(session, "test:///r" + index, new GrantTarget(), 0, 60 * SECOND, 120 * SECOND);
		McpLegacySessionStore.Call overflow = fixture.call(session, 0);
		assertEquals(McpLegacySessionStore.Status.OWNER_CAPACITY,
				fixture.store.beginGrant(overflow, "test:///overflow", fixture.partition, new GrantTarget(), 120 * SECOND).status());
		overflow.physicalComplete(); fixture.store.close(); fixture.assertEmpty();

		Fixture bytesFixture = new Fixture(); Session bytesSession = bytesFixture.session("owner");
		String largeUri = "test:///" + "a".repeat(65_536 - "test:///".length());
		McpLegacySessionStore.Call original = bytesFixture.call(bytesSession, 0);
		McpLegacySessionStore.Grant ignored = bytesFixture.begin(original, largeUri, new GrantTarget(), 120 * SECOND);
		assertTrue(ignored.commit(ignored.generation(), 60 * SECOND)); original.physicalComplete();
		McpLegacySessionStore.Call unsubscribe = bytesFixture.call(bytesSession, 0);
		assertTrue(bytesFixture.store.unsubscribe(unsubscribe, largeUri));
		assertEquals(65_536, bytesFixture.store.grantCounts().retainedUriBytes());
		assertEquals(McpLegacySessionStore.Status.OWNER_CAPACITY, bytesFixture.store.beginGrant(unsubscribe,
				"test:///other", bytesFixture.partition, new GrantTarget(), 120 * SECOND).status());
		ignored.physicalComplete();
		McpLegacySessionStore.Grant replacement = bytesFixture.begin(unsubscribe, "test:///other", new GrantTarget(), 120 * SECOND);
		replacement.abort(); replacement.physicalComplete(); unsubscribe.physicalComplete();
		bytesFixture.store.close(); bytesFixture.assertEmpty();
	}

	@Test
	void a_grant_holds_the_shared_partition_quota_across_get_gaps_and_partition_cannot_migrate() {
		Fixture fixture = new Fixture(); Session session = fixture.session("owner");
		McpLegacySessionStore.Get get = fixture.get(session, Set.of(McpResourceNotificationType.RESOURCE_UPDATED), 60 * SECOND);
		McpLegacySessionStore.Grant grant = fixture.subscribe(session, "test:///resource", new GrantTarget(), 16, 60 * SECOND, 120 * SECOND);
		assertEquals(1, fixture.quota.reservations.get()); get.physicalComplete();
		assertEquals(1, fixture.store.getCounts().quotaRegistrations()); assertEquals(0, fixture.quota.releases.get());
		McpLegacySessionStore.Call call = fixture.call(session, 0);
		assertEquals(McpLegacySessionStore.Status.PARTITION_MISMATCH, fixture.store.beginGrant(call, "test:///new",
				partition("different"), new GrantTarget(), 120 * SECOND).status());
		assertTrue(fixture.store.unsubscribe(call, grant.uri()));
		assertEquals(1, fixture.quota.releases.get()); call.physicalComplete(); fixture.store.close(); fixture.assertEmpty();
	}

	@Test
	void lossOfAnEstablishedGrantRetiresTheSessionWithoutReleasingPhysicalCallbacks() {
		for (String revision : List.of("2025-06-18", "2025-11-25")) {
			for (McpLegacySessionStore.GrantCause cause : List.of(McpLegacySessionStore.GrantCause.AUTHORIZATION_DENIED,
					McpLegacySessionStore.GrantCause.AUTHORIZATION_FAILED, McpLegacySessionStore.GrantCause.LEASE_EXPIRED,
					McpLegacySessionStore.GrantCause.TOTAL_LIFETIME_EXPIRED)) {
				Fixture fixture = new Fixture(revision); Session session = fixture.session("owner"); GrantTarget target = new GrantTarget();
				McpLegacySessionStore.Grant grant = fixture.subscribe(session, "test:///resource", target, 100,
						60 * SECOND, cause == McpLegacySessionStore.GrantCause.TOTAL_LIFETIME_EXPIRED ? 60 * SECOND : 120 * SECOND);
				McpLegacySessionStore.GrantWork work = grant.acquireWork(grant.generation()).orElseThrow();
				McpLegacySessionStore.Get get = fixture.get(session, Set.of(McpResourceNotificationType.RESOURCE_UPDATED), 120 * SECOND);
				if (cause == McpLegacySessionStore.GrantCause.LEASE_EXPIRED || cause == McpLegacySessionStore.GrantCause.TOTAL_LIFETIME_EXPIRED) {
					fixture.now.set(60 * SECOND); fixture.store.maintain();
				} else assertTrue(grant.retireIfCurrent(grant.generation(), cause));
				assertEquals(cause, target.cause.get()); assertFalse(get.active()); assertFalse(grant.active());
				assertEquals(0, fixture.store.counts().liveSessions()); assertEquals(2, fixture.store.counts().physicalReferences());
				assertTrue(fixture.store.counts().retainedBytes() >= 100);
				assertEquals(McpLegacySessionStore.Status.NOT_FOUND, fixture.store.acquire(session.id, session.owner, "/mcp", revision,
						fixture.generation, null, null, REQUEST_TARGET).status());
				assertFalse(grant.renew(grant.generation(), 180 * SECOND));
				get.physicalComplete(); work.close(); work.close(); fixture.assertEmpty();
				fixture.store.close();
			}
		}
	}

	@Test
	void total_and_session_expiry_fence_without_releasing_unfinished_callback_evidence() {
		Fixture fixture = new Fixture(); Session session = fixture.session("owner"); GrantTarget target = new GrantTarget();
		McpLegacySessionStore.Grant grant = fixture.subscribe(session, "test:///resource", target, 10, 120 * SECOND, 120 * SECOND);
		McpLegacySessionStore.GrantWork work = grant.acquireWork(grant.generation()).orElseThrow();
		fixture.now.set(120 * SECOND); fixture.store.maintain();
		assertEquals(McpLegacySessionStore.GrantCause.TOTAL_LIFETIME_EXPIRED, target.cause.get());
		assertEquals(0, fixture.store.grantCounts().logicalGrants()); assertEquals(1, fixture.store.grantCounts().physicalGrantHolds());
		assertTrue(fixture.store.counts().retainedBytes() > 10);
		fixture.store.close(); work.close(); fixture.assertEmpty();
	}

	@Test
	void callbacks_can_reenter_store_and_get_denial_is_generation_scoped() {
		Fixture fixture = new Fixture(); Session session = fixture.session("owner"); AtomicInteger callback = new AtomicInteger();
		GrantTarget target = new GrantTarget() {
			@Override public void fence(long generation) { super.fence(generation); fixture.store.grantCounts(); callback.incrementAndGet(); }
			@Override public void retire(McpLegacySessionStore.GrantCause cause) { super.retire(cause); fixture.store.counts(); callback.incrementAndGet(); }
		};
		fixture.subscribe(session, "test:///resource", target, 0, 60 * SECOND, 120 * SECOND);
		fixture.store.fenceGrants();
		McpLegacySessionStore.Get get = fixture.get(session, Set.of(McpResourceNotificationType.TOOLS_LIST_CHANGED), 60 * SECOND);
		long stale = get.fence(); assertTrue(get.renew(stale, session.owner, fixture.partition, 60 * SECOND,
				Set.of(McpResourceNotificationType.TOOLS_LIST_CHANGED)));
		assertFalse(get.retireIfCurrent(stale, McpLegacySessionStore.GetCause.AUTHORIZATION_DENIED)); assertTrue(get.active());
		get.physicalComplete(); fixture.store.close(); assertEquals(2, callback.get()); fixture.assertEmpty();
	}

	@Test
	void concurrent_flushes_claim_one_writer_and_complete_write_survives_fencing_and_reconnect() {
		for (String revision : List.of("2025-06-18", "2025-11-25"))
			for (McpResourceNotificationType type : List.of(McpResourceNotificationType.TOOLS_LIST_CHANGED,
					McpResourceNotificationType.RESOURCE_UPDATED)) {
				Fixture fixture = new Fixture(revision); Session session = fixture.session("owner");
				fixture.subscribe(session, "test:///resource", new GrantTarget(), 16, 60 * SECOND, 120 * SECOND);
				McpLegacySessionStore.Get first = fixture.get(session, Set.of(type), 60 * SECOND);
				McpLegacySessionStore.Get second = fixture.get(session, Set.of(type), 60 * SECOND);
				if (type == McpResourceNotificationType.RESOURCE_UPDATED) fixture.store.markResourceDirty("/mcp", revision, "test:///resource");
				else fixture.store.markCatalogDirty("/mcp", revision, type);
				McpLegacySessionStore.Delivery snapshot = onlyDelivery(fixture);
				McpLegacySessionStore.DeliveryAttempt a = snapshot.forGet(first).orElseThrow();
				McpLegacySessionStore.DeliveryAttempt b = snapshot.forGet(second).orElseThrow();
				McpLegacySessionStore.NotificationReservation bytes = fixture.store.reserveNotificationBytes(a, 80).reservation().orElseThrow();
				McpLegacySessionStore.NotificationAllocation duplicate = fixture.store.reserveNotificationBytes(b, 80);
				assertEquals(McpLegacySessionStore.NotificationStatus.STALE, duplicate.status());
				assertTrue(duplicate.reservation().isEmpty(), "Concurrent snapshots cannot retain one key on two streams.");
				first.fence();
				assertTrue(fixture.store.pendingDeliveries("/mcp", revision).isEmpty(), "Wait for the original writer's actual disposition.");
				// Its final socket write won before the fence, but notification of that
				// write was deferred until outside the channel lock.
				bytes.written(); bytes.release();
				assertTrue(fixture.store.acknowledgeOffered(a));
				first.physicalComplete(); second.physicalComplete();
				McpLegacySessionStore.Get reconnect = fixture.get(session, Set.of(type), 60 * SECOND);
				assertTrue(fixture.store.pendingDeliveries("/mcp", revision).isEmpty(), "A fully written message has no reconnect replay.");
				reconnect.physicalComplete(); fixture.store.close(); fixture.assertEmpty();
			}
	}

	@Test
	void failed_offer_and_unwritten_disconnect_leave_one_retry_for_another_get() {
		Fixture fixture = new Fixture(); Session session = fixture.session("owner");
		McpLegacySessionStore.Get first = fixture.get(session, Set.of(McpResourceNotificationType.TOOLS_LIST_CHANGED), 60 * SECOND);
		McpLegacySessionStore.Get second = fixture.get(session, Set.of(McpResourceNotificationType.TOOLS_LIST_CHANGED), 60 * SECOND);
		fixture.store.markCatalogDirty("/mcp", REVISION, McpResourceNotificationType.TOOLS_LIST_CHANGED);
		McpLegacySessionStore.DeliveryAttempt failed = onlyDelivery(fixture).forGet(first).orElseThrow();
		fixture.store.reserveNotificationBytes(failed, 80).reservation().orElseThrow().release();
		assertFalse(fixture.store.acknowledgeOffered(failed));
		McpLegacySessionStore.DeliveryAttempt queued = onlyDelivery(fixture).forGet(first).orElseThrow();
		McpLegacySessionStore.NotificationReservation bytes = fixture.store.reserveNotificationBytes(queued, 80).reservation().orElseThrow();
		assertTrue(fixture.store.acknowledgeOffered(queued)); first.physicalComplete();
		assertTrue(fixture.store.pendingDeliveries("/mcp", REVISION).isEmpty());
		bytes.release();
		McpLegacySessionStore.DeliveryAttempt retry = onlyDelivery(fixture).forGet(second).orElseThrow();
		McpLegacySessionStore.NotificationReservation retried = fixture.store.reserveNotificationBytes(retry, 80).reservation().orElseThrow();
		retried.written(); retried.release();
		assertTrue(fixture.store.pendingDeliveries("/mcp", REVISION).isEmpty());
		second.physicalComplete(); fixture.store.close(); fixture.assertEmpty();
	}

	@Test
	void first_page_acknowledgment_preserves_changes_arriving_during_projection_and_write() {
		Fixture fixture = new Fixture(); Session session = fixture.session("owner");
		McpResourceNotificationType family = McpResourceNotificationType.TOOLS_LIST_CHANGED;
		McpLegacySessionStore.Get get = fixture.get(session, Set.of(family), 60 * SECOND);
		fixture.store.markCatalogDirty("/mcp", REVISION, family);
		McpLegacySessionStore.DeliveryAttempt hint = onlyDelivery(fixture).forGet(get).orElseThrow();
		McpLegacySessionStore.NotificationReservation hintBytes = fixture.store.reserveNotificationBytes(hint, 80).reservation().orElseThrow();
		hintBytes.written(); hintBytes.release();
		McpLegacySessionStore.Call list = fixture.call(session, 0);
		McpLegacySessionStore.CatalogReadGeneration read = fixture.store.beginCatalogRead(list, family).orElseThrow();
		fixture.store.markCatalogDirty("/mcp", REVISION, family);
		list.logicalComplete();
		assertFalse(fixture.store.acknowledgeCatalogRead(read));
		assertEquals(1, fixture.store.pendingDeliveries("/mcp", REVISION).size(),
				"A change after a delivered hint must remain eligible after the first-page write.");
		McpLegacySessionStore.Call fresh = fixture.call(session, 0);
		McpLegacySessionStore.CatalogReadGeneration current = fixture.store.beginCatalogRead(fresh, family).orElseThrow();
		assertTrue(fixture.store.acknowledgeCatalogRead(current));
		assertTrue(fixture.store.pendingDeliveries("/mcp", REVISION).isEmpty());
		list.physicalComplete(); fresh.physicalComplete(); get.physicalComplete(); fixture.store.close(); fixture.assertEmpty();
	}

	@Test
	void catalog_dirty_state_coalesces_until_fresh_list_without_replaying_on_reconnect() {
		Fixture fixture = new Fixture(); Session session = fixture.session("owner");
		McpLegacySessionStore.Get first = fixture.get(session, Set.of(McpResourceNotificationType.TOOLS_LIST_CHANGED), 60 * SECOND);
		McpLegacySessionStore.Get newest = fixture.get(session, Set.of(McpResourceNotificationType.TOOLS_LIST_CHANGED), 60 * SECOND);
		for (int index = 0; index < 1000; index++) fixture.store.markCatalogDirty("/mcp", REVISION, McpResourceNotificationType.TOOLS_LIST_CHANGED);
		McpLegacySessionStore.Delivery delivery = onlyDelivery(fixture);
		assertEquals(List.of(newest, first), delivery.eligibleGets());
		McpLegacySessionStore.DeliveryAttempt attempt = delivery.forGet(newest).orElseThrow();
		McpLegacySessionStore.NotificationReservation bytes = fixture.store.reserveNotificationBytes(attempt, 80).reservation().orElseThrow();
		assertTrue(fixture.store.acknowledgeOffered(attempt)); assertTrue(fixture.store.pendingDeliveries("/mcp", REVISION).isEmpty());
		bytes.written(); bytes.release(); fixture.store.markCatalogDirty("/mcp", REVISION, McpResourceNotificationType.TOOLS_LIST_CHANGED);
		assertTrue(fixture.store.pendingDeliveries("/mcp", REVISION).isEmpty());
		first.physicalComplete(); newest.physicalComplete();
		McpLegacySessionStore.Get reconnected = fixture.get(session, Set.of(McpResourceNotificationType.TOOLS_LIST_CHANGED), 60 * SECOND);
		assertTrue(fixture.store.pendingDeliveries("/mcp", REVISION).isEmpty());
		McpLegacySessionStore.Call list = fixture.call(session, 0);
		assertTrue(fixture.store.rearmCatalog(list, McpResourceNotificationType.TOOLS_LIST_CHANGED));
		assertTrue(fixture.store.pendingDeliveries("/mcp", REVISION).isEmpty());
		fixture.store.markCatalogDirty("/mcp", REVISION, McpResourceNotificationType.TOOLS_LIST_CHANGED);
		assertEquals(1, fixture.store.pendingDeliveries("/mcp", REVISION).size());
		list.physicalComplete(); reconnected.physicalComplete(); fixture.store.close(); fixture.assertEmpty();
	}

	@Test
	void new_uri_hint_while_an_older_frame_is_queued_stays_dirty_until_an_accepted_new_offer() {
		Fixture fixture = new Fixture(); Session session = fixture.session("owner");
		fixture.subscribe(session, "test:///resource", new GrantTarget(), 16, 60 * SECOND, 120 * SECOND);
		McpLegacySessionStore.Get get = fixture.get(session, Set.of(McpResourceNotificationType.RESOURCE_UPDATED), 60 * SECOND);
		fixture.store.markResourceDirty("/mcp", REVISION, "test:///resource");
		McpLegacySessionStore.DeliveryAttempt old = onlyDelivery(fixture).forGet(get).orElseThrow();
		McpLegacySessionStore.NotificationReservation oldBytes = fixture.store.reserveNotificationBytes(old, 80).reservation().orElseThrow();
		assertTrue(fixture.store.acknowledgeOffered(old));
		fixture.store.markResourceDirty("/mcp", REVISION, "test:///resource"); assertTrue(old.valid());
		assertTrue(fixture.store.pendingDeliveries("/mcp", REVISION).isEmpty(), "The queued key owns one writer until its outcome is known.");
		oldBytes.written(); oldBytes.release();

		McpLegacySessionStore.DeliveryAttempt fresh = onlyDelivery(fixture).forGet(get).orElseThrow();
		McpLegacySessionStore.NotificationReservation freshBytes = fixture.store.reserveNotificationBytes(fresh, 80).reservation().orElseThrow();
		assertTrue(fixture.store.acknowledgeOffered(fresh)); assertTrue(fixture.store.pendingDeliveries("/mcp", REVISION).isEmpty());
		freshBytes.release(); get.physicalComplete(); fixture.store.close(); fixture.assertEmpty();
	}

	@Test
	void permission_and_source_generations_reach_write_guards_without_losing_pending_catalog_hints() {
		Fixture fixture = new Fixture(); Session session = fixture.session("owner"); AtomicBoolean source = new AtomicBoolean(true);
		McpLegacySessionStore.Get get = fixture.get(session, Set.of(McpResourceNotificationType.TOOLS_LIST_CHANGED), 60 * SECOND);
		fixture.store.markCatalogDirty("/mcp", REVISION, McpResourceNotificationType.TOOLS_LIST_CHANGED, source::get);
		McpLegacySessionStore.DeliveryAttempt old = onlyDelivery(fixture).forGet(get).orElseThrow();
		McpLegacySessionStore.NotificationReservation bytes = fixture.store.reserveNotificationBytes(old, 80).reservation().orElseThrow();
		assertTrue(fixture.store.acknowledgeOffered(old)); assertTrue(bytes.valid());
		long fenced = get.fence(); assertFalse(bytes.valid());
		bytes.release(); assertTrue(get.renew(fenced, session.owner, fixture.partition, 60 * SECOND,
				Set.of(McpResourceNotificationType.TOOLS_LIST_CHANGED)));
		McpLegacySessionStore.DeliveryAttempt restored = onlyDelivery(fixture).forGet(get).orElseThrow();
		assertTrue(restored.valid()); source.set(false); assertFalse(restored.valid());
		fixture.store.markCatalogDirty("/mcp", REVISION, McpResourceNotificationType.PROMPTS_LIST_CHANGED, source::get);
		AtomicBoolean replacementSource = new AtomicBoolean(true);
		fixture.store.markCatalogDirty("/mcp", REVISION, McpResourceNotificationType.TOOLS_LIST_CHANGED, replacementSource::get);
		assertTrue(onlyDelivery(fixture).forGet(get).orElseThrow().valid());
		get.physicalComplete(); fixture.store.close(); fixture.assertEmpty();
	}

	@Test
	void written_catalog_hint_remains_suppressed_on_renewal_and_selected_families_can_narrow() {
		Fixture fixture = new Fixture(); Session session = fixture.session("owner");
		McpLegacySessionStore.Get get = fixture.get(session, Set.of(McpResourceNotificationType.TOOLS_LIST_CHANGED), 60 * SECOND);
		fixture.store.markCatalogDirty("/mcp", REVISION, McpResourceNotificationType.TOOLS_LIST_CHANGED);
		fixture.store.markCatalogDirty("/mcp", REVISION, McpResourceNotificationType.PROMPTS_LIST_CHANGED);
		McpLegacySessionStore.DeliveryAttempt written = onlyDelivery(fixture).forGet(get).orElseThrow();
		McpLegacySessionStore.NotificationReservation bytes = fixture.store.reserveNotificationBytes(written, 80).reservation().orElseThrow();
		assertTrue(fixture.store.acknowledgeOffered(written)); bytes.written(); bytes.release();
		long fenced = get.fence(); assertTrue(get.renew(fenced, session.owner, fixture.partition, 60 * SECOND,
				Set.of(McpResourceNotificationType.TOOLS_LIST_CHANGED)));
		assertTrue(fixture.store.pendingDeliveries("/mcp", REVISION).isEmpty());
		assertTrue(get.renew(get.generation(), session.owner, fixture.partition, 60 * SECOND,
				Set.of(McpResourceNotificationType.PROMPTS_LIST_CHANGED)));
		assertFalse(written.valid()); assertEquals(McpResourceNotificationType.PROMPTS_LIST_CHANGED, onlyDelivery(fixture).notificationType());
		get.physicalComplete(); fixture.store.close(); fixture.assertEmpty();
	}

	@Test
	void written_uri_hint_is_not_repeated_by_grant_renewal_reconciliation_or_duplicate_subscribe() {
		for (String revision : List.of("2025-06-18", "2025-11-25")) {
			Fixture fixture = new Fixture(revision); Session session = fixture.session("owner");
			McpLegacySessionStore.Grant grant = fixture.subscribe(session, "test:///resource", new GrantTarget(), 16, 60 * SECOND, 120 * SECOND);
			McpLegacySessionStore.Get get = fixture.get(session, Set.of(McpResourceNotificationType.RESOURCE_UPDATED), 60 * SECOND);
			fixture.store.markResourceDirty("/mcp", revision, grant.uri());
			McpLegacySessionStore.DeliveryAttempt written = onlyDelivery(fixture).forGet(get).orElseThrow();
			McpLegacySessionStore.NotificationReservation bytes = fixture.store.reserveNotificationBytes(written, 80).reservation().orElseThrow();
			assertTrue(fixture.store.acknowledgeOffered(written)); bytes.written(); bytes.release();
			for (int renewal = 1; renewal <= 3; renewal++) {
				fixture.now.set(renewal * SECOND);
				assertTrue(grant.renew(grant.generation(), fixture.now.get() + 60 * SECOND));
				assertTrue(fixture.store.pendingDeliveries("/mcp", revision).isEmpty(), "A lease renewal is not a new resource invalidation.");
			}
			fixture.store.fenceGrants(); assertFalse(written.valid());
			assertTrue(grant.renew(grant.generation(), 70 * SECOND));
			assertTrue(fixture.store.pendingDeliveries("/mcp", revision).isEmpty());
			McpLegacySessionStore.Call duplicateCall = fixture.call(session, 16);
			McpLegacySessionStore.Grant duplicate = fixture.begin(duplicateCall, grant.uri(), new GrantTarget(), 120 * SECOND);
			assertTrue(duplicate.commit(duplicate.generation(), 70 * SECOND));
			duplicateCall.physicalComplete(); duplicate.physicalComplete();
			assertTrue(fixture.store.pendingDeliveries("/mcp", revision).isEmpty());
			fixture.store.markResourceDirty("/mcp", revision, duplicate.uri());
			assertTrue(onlyDelivery(fixture).forGet(get).isPresent(), "A subsequent publisher invalidation must still be offered.");
			get.physicalComplete(); fixture.store.close(); fixture.assertEmpty();
		}
	}

	@Test
	void queued_uri_hint_is_reoffered_after_grant_generation_changes_even_if_old_release_is_late() {
		for (String revision : List.of("2025-06-18", "2025-11-25"))
			for (String transition : List.of("renew", "reconcile", "duplicate")) {
				Fixture fixture = new Fixture(revision); Session session = fixture.session("owner");
				McpLegacySessionStore.Grant grant = fixture.subscribe(session, "test:///resource", new GrantTarget(), 16, 60 * SECOND, 120 * SECOND);
				McpLegacySessionStore.Get get = fixture.get(session, Set.of(McpResourceNotificationType.RESOURCE_UPDATED), 60 * SECOND);
				fixture.store.markResourceDirty("/mcp", revision, grant.uri());
				McpLegacySessionStore.DeliveryAttempt old = onlyDelivery(fixture).forGet(get).orElseThrow();
				McpLegacySessionStore.NotificationReservation oldBytes = fixture.store.reserveNotificationBytes(old, 80).reservation().orElseThrow();
				assertTrue(fixture.store.acknowledgeOffered(old)); assertTrue(oldBytes.valid());
				if (transition.equals("duplicate")) {
					McpLegacySessionStore.Call duplicateCall = fixture.call(session, 16);
					McpLegacySessionStore.Grant duplicate = fixture.begin(duplicateCall, grant.uri(), new GrantTarget(), 120 * SECOND);
					assertFalse(oldBytes.valid()); assertTrue(duplicate.commit(duplicate.generation(), 70 * SECOND));
					duplicateCall.physicalComplete(); duplicate.physicalComplete();
				} else {
					if (transition.equals("reconcile")) { fixture.store.fenceGrants(); assertFalse(oldBytes.valid()); }
					assertTrue(grant.renew(grant.generation(), 70 * SECOND));
				}
				assertFalse(oldBytes.valid()); assertFalse(fixture.store.acknowledgeOffered(old));
				assertTrue(fixture.store.pendingDeliveries("/mcp", revision).isEmpty(), "A fenced writer must report its outcome before another stream can claim the same notification.");
				oldBytes.release();
				McpLegacySessionStore.DeliveryAttempt replacement = onlyDelivery(fixture).forGet(get).orElseThrow();
				McpLegacySessionStore.NotificationReservation replacementBytes = fixture.store.reserveNotificationBytes(replacement, 80).reservation().orElseThrow();
				assertTrue(fixture.store.acknowledgeOffered(replacement));
				assertEquals(80, fixture.store.grantCounts().queuedNotificationBytes());
				oldBytes.release(); oldBytes.release(); assertTrue(replacementBytes.valid());
				assertEquals(80, fixture.store.grantCounts().queuedNotificationBytes());
				assertTrue(fixture.store.pendingDeliveries("/mcp", revision).isEmpty());
				replacementBytes.release(); replacementBytes.release();
				assertEquals(0, fixture.store.grantCounts().queuedNotificationBytes());
				get.physicalComplete(); fixture.store.close(); fixture.assertEmpty();
			}
	}

	@Test
	void release_before_offer_acknowledgement_preserves_only_a_current_written_uri_hint() {
		for (String revision : List.of("2025-06-18", "2025-11-25")) {
			Fixture fixture = new Fixture(revision); Session session = fixture.session("owner");
			McpLegacySessionStore.Grant grant = fixture.subscribe(session, "test:///resource", new GrantTarget(), 16, 60 * SECOND, 120 * SECOND);
			McpLegacySessionStore.Get get = fixture.get(session, Set.of(McpResourceNotificationType.RESOURCE_UPDATED), 60 * SECOND);
			fixture.store.markResourceDirty("/mcp", revision, grant.uri());
			McpLegacySessionStore.DeliveryAttempt written = onlyDelivery(fixture).forGet(get).orElseThrow();
			McpLegacySessionStore.NotificationReservation writtenBytes = fixture.store.reserveNotificationBytes(written, 80).reservation().orElseThrow();
			writtenBytes.written(); writtenBytes.release(); // A fast writer can drain before ACCEPTED returns.
			assertTrue(fixture.store.acknowledgeOffered(written));
			assertTrue(grant.renew(grant.generation(), 70 * SECOND));
			assertTrue(fixture.store.pendingDeliveries("/mcp", revision).isEmpty());
			fixture.store.markResourceDirty("/mcp", revision, grant.uri());
			McpLegacySessionStore.DeliveryAttempt revoked = onlyDelivery(fixture).forGet(get).orElseThrow();
			McpLegacySessionStore.NotificationReservation revokedBytes = fixture.store.reserveNotificationBytes(revoked, 80).reservation().orElseThrow();
			assertTrue(grant.renew(grant.generation(), 80 * SECOND));
			revokedBytes.release(); assertFalse(fixture.store.acknowledgeOffered(revoked));
			assertTrue(onlyDelivery(fixture).forGet(get).isPresent(), "A dropped stale frame cannot acknowledge the dirty state.");
			get.physicalComplete(); fixture.store.close(); fixture.assertEmpty();
		}
	}

	@Test
	void coalesced_uri_hint_survives_a_written_older_hint_and_later_grant_renewal() {
		for (String revision : List.of("2025-06-18", "2025-11-25")) {
			Fixture fixture = new Fixture(revision); Session session = fixture.session("owner");
			McpLegacySessionStore.Grant grant = fixture.subscribe(session, "test:///resource", new GrantTarget(), 16, 60 * SECOND, 120 * SECOND);
			McpLegacySessionStore.Get get = fixture.get(session, Set.of(McpResourceNotificationType.RESOURCE_UPDATED), 60 * SECOND);
			fixture.store.markResourceDirty("/mcp", revision, grant.uri());
			McpLegacySessionStore.DeliveryAttempt old = onlyDelivery(fixture).forGet(get).orElseThrow();
			McpLegacySessionStore.NotificationReservation oldBytes = fixture.store.reserveNotificationBytes(old, 80).reservation().orElseThrow();
			assertTrue(fixture.store.acknowledgeOffered(old));
			fixture.store.markResourceDirty("/mcp", revision, grant.uri());
			assertTrue(fixture.store.pendingDeliveries("/mcp", revision).isEmpty());
			oldBytes.written(); oldBytes.release(); assertTrue(grant.renew(grant.generation(), 70 * SECOND));
			McpLegacySessionStore.DeliveryAttempt fresh = onlyDelivery(fixture).forGet(get).orElseThrow();
			McpLegacySessionStore.NotificationReservation freshBytes = fixture.store.reserveNotificationBytes(fresh, 80).reservation().orElseThrow();
			assertTrue(fixture.store.acknowledgeOffered(fresh)); freshBytes.written(); freshBytes.release();
			assertTrue(grant.renew(grant.generation(), 80 * SECOND));
			assertTrue(fixture.store.pendingDeliveries("/mcp", revision).isEmpty());
			get.physicalComplete(); fixture.store.close(); fixture.assertEmpty();
		}
	}

	@Test
	void uri_guard_requires_current_grant_and_get_but_unsubscribe_does_not_revoke_other_families() {
		Fixture fixture = new Fixture(); Session session = fixture.session("owner");
		McpLegacySessionStore.Grant grant = fixture.subscribe(session, "test:///resource", new GrantTarget(), 16, 60 * SECOND, 120 * SECOND);
		McpLegacySessionStore.Get get = fixture.get(session, Set.of(McpResourceNotificationType.RESOURCE_UPDATED,
				McpResourceNotificationType.TOOLS_LIST_CHANGED), 60 * SECOND);
		fixture.store.markResourceDirty("/mcp", REVISION, grant.uri());
		McpLegacySessionStore.DeliveryAttempt uri = onlyDelivery(fixture).forGet(get).orElseThrow(); assertTrue(uri.valid());
		long generation = grant.generation(); assertTrue(grant.fenceIfCurrent(generation)); assertFalse(uri.valid());
		assertTrue(fixture.store.pendingDeliveries("/mcp", REVISION).isEmpty());
		assertTrue(grant.renew(grant.generation(), 60 * SECOND));
		McpLegacySessionStore.DeliveryAttempt renewed = onlyDelivery(fixture).forGet(get).orElseThrow(); assertTrue(renewed.valid());
		fixture.store.markCatalogDirty("/mcp", REVISION, McpResourceNotificationType.TOOLS_LIST_CHANGED);
		McpLegacySessionStore.DeliveryAttempt catalog = fixture.store.pendingDeliveries("/mcp", REVISION).stream()
				.filter(value -> value.notificationType() == McpResourceNotificationType.TOOLS_LIST_CHANGED).findFirst().orElseThrow().forGet(get).orElseThrow();
		McpLegacySessionStore.Call unsubscribe = fixture.call(session, 0); assertTrue(fixture.store.unsubscribe(unsubscribe, grant.uri()));
		assertFalse(renewed.valid()); assertTrue(catalog.valid()); assertTrue(get.active());
		unsubscribe.physicalComplete(); get.physicalComplete(); fixture.store.close(); fixture.assertEmpty();
	}

	@Test
	void notification_bytes_enforce_exact_owner_and_global_ceilings_and_keep_retired_owner_accounting() {
		Fixture fixture = new Fixture(); List<McpLegacySessionStore.Get> gets = new ArrayList<>();
		List<McpLegacySessionStore.NotificationReservation> bytes = new ArrayList<>();
		for (int index = 0; index < 9; index++) {
			Session session = fixture.session("owner" + index);
			gets.add(fixture.get(session, Set.of(McpResourceNotificationType.TOOLS_LIST_CHANGED, McpResourceNotificationType.PROMPTS_LIST_CHANGED), 60 * SECOND));
		}
		fixture.store.markCatalogDirty("/mcp", REVISION, McpResourceNotificationType.TOOLS_LIST_CHANGED);
		List<McpLegacySessionStore.Delivery> deliveries = fixture.store.pendingDeliveries("/mcp", REVISION);
		for (int index = 0; index < 8; index++) {
			McpLegacySessionStore.DeliveryAttempt attempt = deliveries.get(index).forGet(gets.get(index)).orElseThrow();
			bytes.add(fixture.store.reserveNotificationBytes(attempt, 2_097_152).reservation().orElseThrow());
			assertTrue(fixture.store.notificationOwnerCapacityExceeded(gets.get(index), 1));
			assertEquals(McpLegacySessionStore.NotificationStatus.STALE,
					fixture.store.reserveNotificationBytes(attempt, 1).status());
			fixture.store.markCatalogDirty("/mcp", REVISION, McpResourceNotificationType.PROMPTS_LIST_CHANGED);
			McpLegacySessionStore.Get get = gets.get(index);
			McpLegacySessionStore.DeliveryAttempt fresh = fixture.store.pendingDeliveries("/mcp", REVISION).stream()
					.filter(delivery -> delivery.notificationType() == McpResourceNotificationType.PROMPTS_LIST_CHANGED
							&& delivery.eligibleGets().contains(get)).findFirst().orElseThrow().forGet(get).orElseThrow();
			assertEquals(McpLegacySessionStore.NotificationStatus.OWNER_CAPACITY,
					fixture.store.reserveNotificationBytes(fresh, 1).status());
		}
		McpLegacySessionStore.DeliveryAttempt global = deliveries.get(8).forGet(gets.get(8)).orElseThrow();
		assertFalse(fixture.store.notificationOwnerCapacityExceeded(gets.get(8), 1));
		assertEquals(McpLegacySessionStore.NotificationStatus.GLOBAL_CAPACITY,
				fixture.store.reserveNotificationBytes(global, 1).status());
		assertEquals(16_777_216, fixture.store.grantCounts().queuedNotificationBytes());
		for (McpLegacySessionStore.Get get : gets) get.physicalComplete(); fixture.store.close();
		assertEquals(8, fixture.store.counts().owners()); assertEquals(0, fixture.store.counts().retainedBytes());
		for (McpLegacySessionStore.NotificationReservation reservation : bytes) {
			assertFalse(reservation.valid()); reservation.release(); reservation.release();
		}
		fixture.assertEmpty();
	}

	@Test
	void maintenance_demand_replacement_transfers_units_and_shorter_renewal_cannot_oversubscribe() {
		Fixture fixture = new Fixture(); List<McpLegacySessionStore.Get> gets = new ArrayList<>();
		Session first = fixture.session("owner0");
		gets.add(fixture.get(first, Set.of(McpResourceNotificationType.TOOLS_LIST_CHANGED), SECOND));
		gets.add(fixture.get(first, Set.of(McpResourceNotificationType.TOOLS_LIST_CHANGED), SECOND));
		for (int index = 1; index < 31; index++) gets.add(fixture.get(fixture.session("owner" + index),
				Set.of(McpResourceNotificationType.TOOLS_LIST_CHANGED), SECOND));
		assertEquals(64_000_000, fixture.store.grantCounts().maintenanceDemandUnits());
		McpLegacySessionStore.Get current = gets.get(1); long generation = current.generation();
		assertEquals(McpLegacySessionStore.Status.GLOBAL_CAPACITY, current.renewStatus(generation, first.owner,
				fixture.partition, SECOND / 2, Set.of(McpResourceNotificationType.TOOLS_LIST_CHANGED)));
		assertEquals(generation, current.generation()); assertTrue(current.active());
		gets.add(fixture.get(first, Set.of(McpResourceNotificationType.TOOLS_LIST_CHANGED), SECOND));
		assertFalse(gets.get(0).active()); assertEquals(64_000_000, fixture.store.grantCounts().maintenanceDemandUnits());
		McpLegacySessionStore.Call call = fixture.call(first, 0);
		McpLegacySessionStore.Grant pending = fixture.begin(call, "test:///resource", new GrantTarget(), 120 * SECOND);
		assertEquals(McpLegacySessionStore.Status.GLOBAL_CAPACITY, pending.commitStatus(pending.generation(), SECOND));
		pending.abort(); call.physicalComplete(); pending.physicalComplete(); fixture.store.close();
		assertEquals(0, fixture.store.grantCounts().maintenanceDemandUnits());
		for (McpLegacySessionStore.Get get : gets) get.physicalComplete(); fixture.assertEmpty();
	}

	@Test
	void grant_rate_capacity_has_no_late_establishment_and_duplicate_keeps_one_rate_registration() {
		Fixture fixture = new Fixture(); Session session = fixture.session("owner");
		for (int index = 0; index < 32; index++) fixture.subscribe(session, "test:///r" + index, new GrantTarget(), 0, SECOND, 120 * SECOND);
		assertEquals(64_000_000, fixture.store.grantCounts().maintenanceDemandUnits());
		McpLegacySessionStore.Call call = fixture.call(session, 0);
		McpLegacySessionStore.Grant duplicate = fixture.begin(call, "test:///r0", new GrantTarget(), 240 * SECOND);
		assertTrue(duplicate.commit(duplicate.generation(), SECOND)); assertEquals(64_000_000, fixture.store.grantCounts().maintenanceDemandUnits());
		call.physicalComplete(); duplicate.physicalComplete();
		McpLegacySessionStore.Call overflow = fixture.call(session, 0);
		McpLegacySessionStore.Grant rejected = fixture.begin(overflow, "test:///overflow", new GrantTarget(), 120 * SECOND);
		long generation = rejected.generation(); assertEquals(McpLegacySessionStore.Status.GLOBAL_CAPACITY, rejected.commitStatus(generation, SECOND));
		rejected.abort(); assertFalse(rejected.commit(generation, 60 * SECOND));
		overflow.physicalComplete(); rejected.physicalComplete(); fixture.store.close(); fixture.assertEmpty();
	}

	@Test
	void authorizer_body_gate_survives_duplicate_unsubscribe_and_recreate_until_old_body_returns() {
		Fixture fixture = new Fixture(); Session session = fixture.session("owner");
		McpLegacySessionStore.Grant first = fixture.subscribe(session, "test:///resource", new GrantTarget(), 16, 60 * SECOND, 120 * SECOND);
		McpLegacySessionStore.GrantWork oldWork = first.acquireWork(first.generation()).orElseThrow();
		assertTrue(first.tryBeginAuthorization()); assertFalse(first.tryBeginAuthorization());
		McpLegacySessionStore.Call duplicateCall = fixture.call(session, 16);
		McpLegacySessionStore.Grant duplicate = fixture.begin(duplicateCall, "TEST:///resource", new GrantTarget(), 120 * SECOND);
		assertFalse(duplicate.tryBeginAuthorization());
		assertTrue(fixture.store.unsubscribe(duplicateCall, "test:///resource"));
		McpLegacySessionStore.Grant recreated = fixture.begin(duplicateCall, "test:///resource", new GrantTarget(), 120 * SECOND);
		assertFalse(recreated.tryBeginAuthorization()); assertFalse(first.tryBeginAuthorization());
		first.finishAuthorization(); assertTrue(recreated.tryBeginAuthorization());
		first.finishAuthorization(); assertFalse(recreated.tryBeginAuthorization()); // Stale finish cannot clear the new body.
		recreated.finishAuthorization(); assertTrue(recreated.commit(recreated.generation(), 60 * SECOND));
		oldWork.close(); duplicate.physicalComplete(); recreated.physicalComplete(); duplicateCall.physicalComplete();
		fixture.store.close(); fixture.assertEmpty();
	}

	@Test
	void owner_grant_ceiling_spans_sessions_and_global_ceiling_spans_owners() {
		Fixture fixture = new Fixture(); Session first = fixture.session("owner0");
		for (int index = 0; index < 64; index++) fixture.subscribe(first, "test:///r" + index, new GrantTarget(), 0, 60 * SECOND, 120 * SECOND);
		Session sameOwner = fixture.session("owner0"); McpLegacySessionStore.Call ownerOverflow = fixture.call(sameOwner, 0);
		assertEquals(McpLegacySessionStore.Status.OWNER_CAPACITY, fixture.store.beginGrant(ownerOverflow,
				"test:///other", fixture.partition, new GrantTarget(), 120 * SECOND).status()); ownerOverflow.physicalComplete();
		for (int owner = 1; owner < 8; owner++) {
			Session session = fixture.session("owner" + owner);
			for (int index = 0; index < 64; index++) fixture.subscribe(session, "test:///r" + index, new GrantTarget(), 0, 60 * SECOND, 120 * SECOND);
		}
		assertEquals(512, fixture.store.grantCounts().logicalGrants());
		McpLegacySessionStore.Call globalOverflow = fixture.call(fixture.session("owner8"), 0);
		assertEquals(McpLegacySessionStore.Status.GLOBAL_CAPACITY, fixture.store.beginGrant(globalOverflow,
				"test:///other", fixture.partition, new GrantTarget(), 120 * SECOND).status());
		globalOverflow.physicalComplete(); fixture.store.close(); fixture.assertEmpty();
	}

	private static McpLegacySessionStore.Delivery onlyDelivery(Fixture fixture) {
		List<McpLegacySessionStore.Delivery> result = fixture.store.pendingDeliveries("/mcp", fixture.revision);
		assertEquals(1, result.size()); return result.get(0);
	}

	private static McpEffectivePartition partition(String key) {
		return new McpEffectivePartition(new McpEndpointPartitionIdentity("/mcp"), McpPartitionPurpose.AUTHORIZATION, Optional.of(key));
	}
	private record Session(McpLegacySessionStore.Owner owner, String id) {}
	private static class GrantTarget implements McpLegacySessionStore.GrantTarget {
		private final AtomicInteger fences = new AtomicInteger();
		private final AtomicInteger retirements = new AtomicInteger();
		private final AtomicReference<McpLegacySessionStore.GrantCause> cause = new AtomicReference<>();
		@Override public void fence(long generation) { fences.incrementAndGet(); }
		@Override public void retire(McpLegacySessionStore.GrantCause value) { cause.set(value); retirements.incrementAndGet(); }
	}
	private static final class Quota implements McpLegacySessionStore.GetQuota {
		private final AtomicInteger reservations = new AtomicInteger();
		private final AtomicInteger releases = new AtomicInteger();
		private final Map<McpEffectivePartition, Integer> active = new HashMap<>();
		@Override public boolean reserve(McpEffectivePartition partition) { reservations.incrementAndGet(); active.merge(partition, 1, Integer::sum); return true; }
		@Override public void release(McpEffectivePartition partition) { releases.incrementAndGet(); active.compute(partition, (key, value) -> value == 1 ? null : value - 1); }
	}
	private static final class Fixture {
		private final String revision;
		private final AtomicLong now = new AtomicLong();
		private final AtomicInteger tokens = new AtomicInteger();
		private final AtomicInteger requests = new AtomicInteger();
		private final Object generation = new Object();
		private final Quota quota = new Quota();
		private final McpEffectivePartition partition = partition("shared");
		private final McpLegacySessionStore store = new McpLegacySessionStore(new McpLegacySessionStore.Config(
				32, 4, 300 * SECOND, 600 * SECOND, 65_536, false, 1_048_576, 2_097_152, 16_777_216),
				McpJsonLimits.productionDefaults(), now::get, () -> ByteBuffer.allocate(32).putInt(tokens.incrementAndGet()).array());
		private Fixture() { this(REVISION); }
		private Fixture(String revision) { this.revision = revision; store.configureGetQuota(quota); }
		private Session session(String ownerKey) {
			McpLegacySessionStore.Owner owner = new McpLegacySessionStore.Owner(ownerKey, false);
			McpLegacySessionStore.Initialization init = store.publish(owner, "/mcp", revision, generation,
					new McpLegacySessionStore.Snapshot(McpClientCapabilities.empty(), Optional.empty()), REQUEST_TARGET).initialization().orElseThrow();
			init.physicalComplete(); Session result = new Session(owner, init.sessionId());
			McpLegacySessionStore.Call acknowledged = call(result, 0); assertTrue(store.acknowledge(acknowledged)); acknowledged.physicalComplete();
			return result;
		}
		private McpLegacySessionStore.Call call(Session session, long bytes) {
			McpLegacySessionStore.Call call = store.acquire(session.id, session.owner, "/mcp", revision, generation,
					new McpJsonRpcId.StringId(Integer.toString(requests.incrementAndGet())), null, REQUEST_TARGET, bytes).call().orElseThrow();
			assertTrue(call.acceptedUse()); return call;
		}
		private McpLegacySessionStore.Grant begin(McpLegacySessionStore.Call call, String uri, GrantTarget target, long total) {
			McpLegacySessionStore.GrantAllocation allocation = store.beginGrant(call, uri, partition, target, total);
			assertEquals(McpLegacySessionStore.Status.ACCEPTED, allocation.status()); return allocation.grant().orElseThrow();
		}
		private McpLegacySessionStore.Grant subscribe(Session session, String uri, GrantTarget target, long bytes, long lease, long total) {
			McpLegacySessionStore.Call call = call(session, bytes); McpLegacySessionStore.Grant grant = begin(call, uri, target, total);
			assertTrue(grant.commit(grant.generation(), lease)); call.physicalComplete(); grant.physicalComplete(); return grant;
		}
		private McpLegacySessionStore.Get get(Session session, Set<McpResourceNotificationType> families, long lease) {
			return store.reserveGet(session.id, session.owner, "/mcp", revision, generation, partition, new McpLegacySessionStore.GetTarget() {
				@Override public void retire(McpLegacySessionStore.GetCause cause) {}
				@Override public void fence(long generation) {}
			}, 0, lease, 120 * SECOND, families).get().orElseThrow();
		}
		private void assertEmpty() {
			assertEquals(new McpLegacySessionStore.Counts(0, 0, 0, 0, 0), store.counts());
			assertEquals(new McpLegacySessionStore.GrantCounts(0, 0, 0, 0, 0), store.grantCounts());
			assertEquals(new McpLegacySessionStore.GetCounts(0, 0, 0), store.getCounts());
			assertTrue(quota.active.isEmpty());
		}
	}
}
