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

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.soklet.internal.mcp.protocol.McpLegacySessionStore.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class McpLegacySessionStoreTests {
	private static final String JUNE = "2025-06-18";
	private static final String NOVEMBER = "2025-11-25";
	private static final long SECOND = 1_000_000_000L;
	private final AtomicLong now = new AtomicLong();
	private final AtomicInteger tokens = new AtomicInteger();
	private final Object generation = new Object();
	private final McpLegacySessionStore.Owner owner = new McpLegacySessionStore.Owner("issuer:tenant:subject", false);
	private final McpLegacySessionStore.Snapshot snapshot = new McpLegacySessionStore.Snapshot(
			McpClientCapabilities.builder().unknown("tasks", McpJsonObject.empty())
					.unknown("peerExtension", new McpJsonObject(Map.of("offered", McpJsonBoolean.TRUE))).build(),
			Optional.of(McpImplementationMetadata.withNameAndVersion("private-client", "1")));

	@Test
	void publication_is_usable_before_ack_and_retains_only_the_public_snapshot() {
		for (String revision : List.of(JUNE, NOVEMBER)) {
			McpLegacySessionStore store = store(config(4, 4));
			McpLegacySessionStore.Initialization initialization = publish(store, revision);
			assertEquals(43, initialization.sessionId().length());
			assertTrue(initialization.sessionId().matches("[A-Za-z0-9_-]{43}"));
			McpLegacySessionStore.Call call = acquire(store, initialization, revision, integer(1), null, new Target());
			assertSame(snapshot, call.snapshot());
			assertTrue(call.snapshot().clientCapabilities().unknownCapabilities().containsKey("tasks"));
			assertTrue(call.acceptedUse());
			assertTrue(store.acknowledge(call));
			assertTrue(store.acknowledge(call));
			call.logicalComplete();
			call.physicalComplete();
			initialization.physicalComplete();
			assertEquals(1, store.counts().liveSessions());
			assertEquals(0, store.counts().physicalReferences());
			store.close();
			assertEquals(0, store.counts().retainedBytes());
		}
	}

	@Test
	void owner_path_and_generation_are_neutral_before_revision_is_disclosed() {
		McpLegacySessionStore store = store(config(4, 4));
		McpLegacySessionStore.Initialization initialization = publish(store, JUNE);
		String id = initialization.sessionId();
		assertEquals(NOT_FOUND, store.acquire(id, new McpLegacySessionStore.Owner("other", false), "/mcp", NOVEMBER,
				generation, integer(1), null, new Target()).status());
		assertEquals(NOT_FOUND, store.acquire(id, owner, "/other", NOVEMBER, generation, integer(1), null, new Target()).status());
		assertEquals(NOT_FOUND, store.acquire(id, owner, "/mcp", JUNE, new Object(), integer(1), null, new Target()).status());
		assertEquals(REVISION_MISMATCH, store.acquire(id, owner, "/mcp", NOVEMBER, generation, integer(1), null, new Target()).status());
		assertEquals(NOT_FOUND, store.acquire("foreign-format!", owner, "/mcp", JUNE, generation, integer(1), null, new Target()).status());
		assertEquals(1, store.counts().liveSessions());
		assertEquals(1, store.counts().physicalReferences());
	}

	@Test
	void header_and_owner_validation_are_utf8_exact_and_do_not_echo_sensitive_values() {
		for (String invalid : List.of("", "has space", "tab\t", "line\r\n", "é", "x".repeat(257)))
			assertFalse(McpLegacySessionStore.validSessionId(invalid));
		assertTrue(McpLegacySessionStore.validSessionId("different-server-format!"));
		assertTrue(McpLegacySessionStore.validSessionId("!".repeat(256)));
		for (String invalid : List.of("", " ", "é".repeat(129), "\uD800", "\uDC00"))
			assertThrows(IllegalArgumentException.class, () -> new McpLegacySessionStore.Owner(invalid, false));
		assertDoesNotThrow(() -> new McpLegacySessionStore.Owner("é".repeat(128), false));
		McpLegacySessionStore store = store(config(4, 4));
		McpLegacySessionStore.Initialization initialization = publish(store, JUNE);
		assertFalse(owner.toString().contains(owner.key()));
		assertFalse(snapshot.toString().contains("private-client"));
		assertFalse(initialization.toString().contains(initialization.sessionId()));
		assertFalse(store.acquire(initialization.sessionId(), owner, "/mcp", JUNE, generation,
				integer(1), null, new Target()).toString().contains(initialization.sessionId()));
		assertThrows(IllegalArgumentException.class, () -> store.publish(owner, "/mcp", "2026-07-28", generation, snapshot, new Target()));
		assertThrows(IllegalArgumentException.class, () -> store.acquire(initialization.sessionId(), owner, "/mcp", "2026-07-28",
				generation, integer(2), null, new Target()));
	}

	@Test
	void unaccepted_lookup_does_not_prove_delivery_or_renew_the_ack_window() {
		McpLegacySessionStore store = store(config(4, 4));
		McpLegacySessionStore.Initialization initialization = publish(store, JUNE);
		now.set(29 * SECOND);
		McpLegacySessionStore.Call unaccepted = acquire(store, initialization, JUNE, integer(1), null, new Target());
		unaccepted.physicalComplete();
		initialization.physicalComplete();
		now.set(30 * SECOND);
		store.maintain();
		assertEquals(0, store.counts().liveSessions());
		assertEquals(0, store.counts().retainedBytes());
		assertEquals(NOT_FOUND, store.acquire(initialization.sessionId(), owner, "/mcp", JUNE, generation,
				integer(2), null, new Target()).status());
	}

	@Test
	void completed_accepted_use_proves_delivery_and_late_initial_writer_failure_cannot_revoke_it() {
		McpLegacySessionStore store = store(config(4, 4));
		McpLegacySessionStore.Initialization initialization = publish(store, JUNE);
		McpLegacySessionStore.Call call = acquire(store, initialization, JUNE, integer(1), null, new Target());
		assertTrue(call.acceptedUse());
		call.physicalComplete();
		initialization.physicalComplete();
		now.set(31 * SECOND);
		store.maintain();
		initialization.deliveryFailed();
		assertEquals(1, store.counts().liveSessions());
		McpLegacySessionStore.Call ack = acquire(store, initialization, JUNE, null, null, new Target());
		assertTrue(store.acknowledge(ack));
		ack.physicalComplete();
	}

	@Test
	void failed_initial_delivery_releases_the_logical_slot_but_keeps_evidence_until_physical_exit() {
		McpLegacySessionStore store = store(config(1, 1));
		Target target = new Target();
		McpLegacySessionStore.Initialization initialization = store.publish(owner, "/mcp", JUNE,
				generation, snapshot, target, 300).initialization().orElseThrow();
		long retained = store.counts().retainedBytes();
		initialization.deliveryFailed();
		assertEquals(0, store.counts().liveSessions());
		assertEquals(retained, store.counts().retainedBytes());
		assertEquals(1, target.retirements.get());
		assertEquals(McpLegacySessionStore.Cause.SESSION_CLOSED, target.lastCause.get());
		initialization.deliveryFailed();
		assertEquals(1, target.retirements.get());
		initialization.physicalComplete();
		initialization.physicalComplete();
		assertEquals(0, store.counts().retainedBytes());
		assertEquals(0, store.counts().owners());
		assertEquals(0, store.counts().physicalReferences());
	}

	@Test
	void idle_requires_actual_quiescence_but_hard_lifetime_expires_running_work() {
		McpLegacySessionStore store = store(config(4, 4));
		McpLegacySessionStore.Initialization initialization = publish(store, JUNE);
		Target target = new Target();
		McpLegacySessionStore.Call call = acquire(store, initialization, JUNE, integer(1), null, target);
		call.acceptedUse(); initialization.physicalComplete();
		now.set(150 * SECOND);
		store.maintain();
		assertEquals(1, store.counts().liveSessions());
		call.logicalComplete(); // a blocked worker still physically owns its evidence
		store.maintain();
		assertEquals(1, store.counts().liveSessions());
		call.physicalComplete();
		now.set(269 * SECOND);
		store.maintain();
		assertEquals(1, store.counts().liveSessions());
		now.set(270 * SECOND);
		store.maintain();
		assertEquals(0, store.counts().liveSessions());
		assertEquals(0, target.retirements.get()); // final reservation already made it uncancellable

		now.set(0);
		McpLegacySessionStore.Initialization activeInit = publish(store, NOVEMBER);
		Target activeTarget = new Target();
		McpLegacySessionStore.Call active = acquire(store, activeInit, NOVEMBER, integer(1), null, activeTarget);
		active.acceptedUse(); activeInit.physicalComplete();
		now.set(300 * SECOND);
		store.maintain();
		assertEquals(0, store.counts().liveSessions());
		assertEquals(McpLegacySessionStore.Cause.SESSION_EXPIRED, activeTarget.lastCause.get());
		assertEquals(1, store.counts().physicalReferences());
		assertTrue(store.counts().retainedBytes() > 0);
		active.physicalComplete();
		assertEquals(0, store.counts().retainedBytes());
	}

	@Test
	void quota_reclaims_only_the_same_owners_quiescent_old_record() {
		McpLegacySessionStore store = store(config(2, 1));
		McpLegacySessionStore.Initialization initialization = publish(store, JUNE);
		McpLegacySessionStore.Call proof = acquire(store, initialization, JUNE, null, null, new Target());
		proof.acceptedUse(); proof.physicalComplete(); initialization.physicalComplete();
		assertEquals(OWNER_CAPACITY, store.publish(owner, "/mcp", JUNE, generation, snapshot, new Target()).status());
		now.set(31 * SECOND);
		McpLegacySessionStore.Initialization replacement = publish(store, JUNE);
		assertNotEquals(initialization.sessionId(), replacement.sessionId());
		assertEquals(1, store.counts().liveSessions());
		McpLegacySessionStore.Owner other = new McpLegacySessionStore.Owner("other", false);
		assertEquals(ACCEPTED, store.publish(other, "/mcp", JUNE, generation, snapshot, new Target()).status());
		assertEquals(GLOBAL_CAPACITY, store.publish(new McpLegacySessionStore.Owner("third", false),
				"/mcp", JUNE, generation, snapshot, new Target()).status());
		assertEquals(2, store.counts().liveSessions());
	}

	@Test
	void full_global_count_replaces_only_an_eligible_record_owned_by_the_initializer() {
		McpLegacySessionStore store = store(config(2, 2));
		McpLegacySessionStore.Initialization old = publish(store, JUNE);
		McpLegacySessionStore.Call proof = acquire(store, old, JUNE, null, null, new Target());
		proof.acceptedUse(); proof.physicalComplete(); old.physicalComplete();
		McpLegacySessionStore.Owner other = new McpLegacySessionStore.Owner("other", false);
		McpLegacySessionStore.Initialization unrelated = store.publish(other, "/mcp", JUNE, generation,
				snapshot, new Target()).initialization().orElseThrow();
		McpLegacySessionStore.Call pin = store.acquire(unrelated.sessionId(), other, "/mcp", JUNE,
				generation, integer(1), null, new Target()).call().orElseThrow();
		pin.acceptedUse();
		now.set(31 * SECOND);
		McpLegacySessionStore.Initialization replacement = publish(store, JUNE);
		assertNotEquals(old.sessionId(), replacement.sessionId());
		assertEquals(2, store.counts().liveSessions());
		assertEquals(NOT_FOUND, store.acquire(old.sessionId(), owner, "/mcp", JUNE,
				generation, integer(1), null, new Target()).status());
		assertEquals(ACCEPTED, store.acquire(unrelated.sessionId(), other, "/mcp", JUNE,
				generation, integer(2), null, new Target()).status());
	}

	@Test
	void owner_evidence_pressure_reclaims_multiple_eligible_records_without_evicting_a_physical_worker() {
		McpLegacySessionStore store = store(new McpLegacySessionStore.Config(8, 8,
				120 * SECOND, 300 * SECOND, 65536, false, 1024, 1024, 4096));
		McpLegacySessionStore.Initialization first = publish(store, JUNE);
		long base = store.counts().retainedBytes();
		McpLegacySessionStore.Initialization second = publish(store, JUNE);
		for (McpLegacySessionStore.Initialization initial : List.of(first, second)) {
			McpLegacySessionStore.Call proof = acquire(store, initial, JUNE, null, null, new Target());
			proof.acceptedUse(); proof.physicalComplete(); initial.physicalComplete();
		}
		now.set(31 * SECOND);
		McpLegacySessionStore.Initialization replacement = store.publish(owner, "/mcp", JUNE,
				generation, snapshot, new Target(), 1024 - base).initialization().orElseThrow();
		assertEquals(1, store.counts().liveSessions());
		assertEquals(1024, store.counts().retainedBytes());
		assertEquals(NOT_FOUND, store.acquire(first.sessionId(), owner, "/mcp", JUNE, generation,
				integer(1), null, new Target()).status());
		assertEquals(NOT_FOUND, store.acquire(second.sessionId(), owner, "/mcp", JUNE, generation,
				integer(1), null, new Target()).status());
		assertEquals(OWNER_CAPACITY, store.publish(owner, "/mcp", JUNE, generation, snapshot, new Target()).status());
		replacement.physicalComplete();
		McpLegacySessionStore.Call blockedWorker = acquire(store, replacement, JUNE, integer(1), null, new Target());
		blockedWorker.acceptedUse(); blockedWorker.logicalComplete();
		now.set(62 * SECOND);
		assertEquals(OWNER_CAPACITY, store.publish(owner, "/mcp", JUNE, generation, snapshot, new Target(), 1024 - base).status());
		assertEquals(1, store.counts().liveSessions());
		assertEquals(1, store.counts().physicalReferences());
	}

	@Test
	void global_evidence_pressure_reclaims_the_same_owners_quiescent_record_but_preserves_other_work() {
		McpLegacySessionStore store = store(new McpLegacySessionStore.Config(8, 8,
				120 * SECOND, 300 * SECOND, 65536, false, 1024, 1024, 1024));
		McpLegacySessionStore.Initialization old = publish(store, JUNE);
		McpLegacySessionStore.Call proof = acquire(store, old, JUNE, null, null, new Target());
		proof.acceptedUse(); proof.physicalComplete(); old.physicalComplete();
		McpLegacySessionStore.Owner other = new McpLegacySessionStore.Owner("other", false);
		McpLegacySessionStore.Initialization unrelated = store.publish(other, "/mcp", JUNE,
				generation, snapshot, new Target()).initialization().orElseThrow();
		McpLegacySessionStore.Call active = store.acquire(unrelated.sessionId(), other, "/mcp", JUNE,
				generation, integer(1), null, new Target(), 1024 - store.counts().retainedBytes()).call().orElseThrow();
		active.acceptedUse();
		assertEquals(1024, store.counts().retainedBytes());
		now.set(31 * SECOND);
		McpLegacySessionStore.Initialization replacement = publish(store, JUNE);
		assertNotEquals(old.sessionId(), replacement.sessionId());
		assertEquals(1024, store.counts().retainedBytes());
		assertEquals(2, store.counts().liveSessions());
		assertTrue(active.acceptedUse());
	}

	@Test
	void anonymous_namespace_and_global_subbudget_cannot_consume_authenticated_ownership() {
		McpLegacySessionStore denied = store(config(128, 128));
		McpLegacySessionStore.Owner anonymous = new McpLegacySessionStore.Owner(owner.key(), true);
		assertEquals(ANONYMOUS_DENIED, denied.publish(anonymous, "/mcp", JUNE, generation, snapshot, new Target()).status());
		McpLegacySessionStore.Config enabled = new McpLegacySessionStore.Config(128, 128,
				120 * SECOND, 300 * SECOND, 65536, true, 1 << 20, 2 << 20, 16 << 20);
		McpLegacySessionStore store = store(enabled);
		for (int index = 0; index < 64; index++)
			assertEquals(ACCEPTED, store.publish(anonymous, "/mcp", JUNE, generation, snapshot, new Target()).status());
		assertEquals(GLOBAL_CAPACITY, store.publish(anonymous, "/mcp", JUNE, generation, snapshot, new Target()).status());
		McpLegacySessionStore.Initialization authenticated = publish(store, JUNE);
		assertEquals(NOT_FOUND, store.acquire(authenticated.sessionId(), anonymous, "/mcp", JUNE, generation,
				integer(1), null, new Target()).status());
		assertEquals(65, store.counts().liveSessions());
		assertEquals(64, store.counts().anonymousSessions());
		store.close();
		assertEquals(0, store.counts().anonymousSessions());
	}

	@Test
	void snapshot_overflow_fails_before_any_capacity_or_partial_projection_is_published() {
		McpLegacySessionStore small = store(new McpLegacySessionStore.Config(2, 2, 120 * SECOND,
				300 * SECOND, 32, false, 1024, 2048, 4096));
		assertEquals(METADATA_TOO_LARGE, small.publish(owner, "/mcp", JUNE, generation, snapshot, new Target()).status());
		assertEquals(0, small.counts().liveSessions());
		assertEquals(0, small.counts().retainedBytes());
		List<McpJsonValue> nodes = new ArrayList<>();
		for (int index = 0; index < 4096; index++) nodes.add(McpJsonBoolean.FALSE);
		McpLegacySessionStore.Snapshot tooManyNodes = new McpLegacySessionStore.Snapshot(
				McpClientCapabilities.builder().unknown("many", new McpJsonArray(nodes)).build(), Optional.empty());
		McpLegacySessionStore normal = store(config(4, 4));
		assertEquals(METADATA_TOO_LARGE, normal.publish(owner, "/mcp", JUNE, generation, tooManyNodes, new Target()).status());
		assertEquals(0, normal.counts().physicalReferences());
	}

	@Test
	void request_evidence_stays_charged_through_logical_retirement_and_is_released_once() {
		McpLegacySessionStore.Config limited = new McpLegacySessionStore.Config(4, 4,
				120 * SECOND, 300 * SECOND, 65536, false, 1024, 2048, 4096);
		McpLegacySessionStore store = store(limited);
		McpLegacySessionStore.Initialization initialization = publish(store, JUNE);
		long base = store.counts().retainedBytes();
		McpLegacySessionStore.Call call = store.acquire(initialization.sessionId(), owner, "/mcp", JUNE,
				generation, integer(1), null, new Target(), 600).call().orElseThrow();
		call.acceptedUse(); initialization.physicalComplete();
		call.logicalComplete();
		assertEquals(base + 600, store.counts().retainedBytes());
		assertEquals(OWNER_CAPACITY, store.acquire(initialization.sessionId(), owner, "/mcp", JUNE,
				generation, integer(1), null, new Target(), 600).status());
		store.close();
		assertEquals(0, store.counts().liveSessions());
		assertEquals(base + 600, store.counts().retainedBytes());
		call.physicalComplete(); call.physicalComplete();
		assertEquals(0, store.counts().retainedBytes());
		assertEquals(0, store.counts().owners());
		assertEquals(0, store.counts().physicalReferences());
		assertEquals(OWNER_CAPACITY, store.publish(owner, "/mcp", JUNE, generation, snapshot, new Target(), Long.MAX_VALUE).status());
		assertThrows(IllegalArgumentException.class, () -> store.publish(owner, "/mcp", JUNE, generation, snapshot, new Target(), -1));
	}

	@Test
	void owner_and_global_evidence_caps_apply_across_records_and_generations() {
		McpLegacySessionStore store = store(new McpLegacySessionStore.Config(8, 8,
				120 * SECOND, 300 * SECOND, 65536, false, 1024, 2048, 2048));
		McpLegacySessionStore.Initialization first = store.publish(owner, "/mcp", JUNE,
				generation, snapshot, new Target(), 800).initialization().orElseThrow();
		McpLegacySessionStore.Initialization second = store.publish(owner, "/mcp", NOVEMBER,
				generation, snapshot, new Target(), 800).initialization().orElseThrow();
		assertEquals(OWNER_CAPACITY, store.publish(owner, "/mcp", JUNE, generation, snapshot, new Target(), 800).status());
		assertEquals(GLOBAL_CAPACITY, store.publish(new McpLegacySessionStore.Owner("other", false),
				"/mcp", JUNE, generation, snapshot, new Target(), 800).status());
		store.close();
		assertEquals(0, store.counts().liveSessions());
		assertEquals(GLOBAL_CAPACITY, store.publish(new McpLegacySessionStore.Owner("new", false),
				"/mcp", JUNE, new Object(), snapshot, new Target(), 800).status());
		first.physicalComplete(); second.physicalComplete();
		assertEquals(ACCEPTED, store.publish(owner, "/mcp", JUNE, new Object(), snapshot, new Target(), 800).status());
	}

	@Test
	void active_ids_are_typed_and_bounded_and_completed_ids_have_no_lifetime_history() {
		McpLegacySessionStore store = store(config(4, 4));
		McpLegacySessionStore.Initialization initialization = publish(store, JUNE);
		McpLegacySessionStore.Call integer = acquire(store, initialization, JUNE, integer(1), null, new Target());
		McpLegacySessionStore.Call string = acquire(store, initialization, JUNE, new McpJsonRpcId.StringId("1"), null, new Target());
		assertEquals(ACTIVE_ID_COLLISION, store.acquire(initialization.sessionId(), owner, "/mcp", JUNE,
				generation, integer(1), null, new Target()).status());
		assertEquals(INVALID_ID, store.acquire(initialization.sessionId(), owner, "/mcp", JUNE,
				generation, new McpJsonRpcId.StringId("é".repeat(129)), null, new Target()).status());
		assertEquals(INVALID_ID, store.acquire(initialization.sessionId(), owner, "/mcp", JUNE,
				generation, new McpJsonRpcId.StringId("\uD800"), null, new Target()).status());
		integer.logicalComplete();
		for (int index = 0; index < 512; index++) {
			McpLegacySessionStore.Call reused = acquire(store, initialization, JUNE, integer(1), null, new Target());
			reused.physicalComplete();
		}
		assertEquals(1, store.counts().liveSessions());
		assertEquals(3, store.counts().physicalReferences()); // initialization, old integer worker, string worker
		integer.physicalComplete(); string.physicalComplete(); initialization.physicalComplete();
	}

	@Test
	void progress_collision_is_suppressed_without_rejecting_calls_and_detach_releases_only_the_token() {
		McpLegacySessionStore store = store(config(4, 4));
		McpLegacySessionStore.Initialization initialization = publish(store, NOVEMBER);
		McpProgressToken token = new McpProgressToken.StringToken("1");
		McpLegacySessionStore.Call first = acquire(store, initialization, NOVEMBER, integer(1), token, new Target());
		McpLegacySessionStore.Call collided = acquire(store, initialization, NOVEMBER, integer(2), token, new Target());
		assertTrue(first.progressAllowed()); assertFalse(collided.progressAllowed());
		McpLegacySessionStore.Call typed = acquire(store, initialization, NOVEMBER, integer(3),
				new McpProgressToken.IntegerToken(BigInteger.ONE), new Target());
		assertTrue(typed.progressAllowed());
		first.detachProgress();
		assertFalse(first.progressAllowed());
		assertEquals(ACTIVE_ID_COLLISION, store.acquire(initialization.sessionId(), owner, "/mcp", NOVEMBER,
				generation, integer(1), null, new Target()).status());
		McpLegacySessionStore.Call replacement = acquire(store, initialization, NOVEMBER, integer(4), token, new Target());
		assertTrue(replacement.progressAllowed());
		McpLegacySessionStore.Call oversized = acquire(store, initialization, NOVEMBER, integer(5),
				new McpProgressToken.StringToken("x".repeat(257)), new Target());
		assertFalse(oversized.progressAllowed());
	}

	@Test
	void all_32_active_call_slots_do_not_prevent_an_admitted_cancel_notification() {
		McpLegacySessionStore store = store(config(4, 4));
		McpLegacySessionStore.Initialization initialization = publish(store, JUNE);
		List<McpLegacySessionStore.Call> calls = new ArrayList<>();
		Target first = new Target();
		for (int index = 0; index < 32; index++)
			calls.add(acquire(store, initialization, JUNE, integer(index), null, index == 0 ? first : new Target()));
		assertEquals(CALL_CAPACITY, store.acquire(initialization.sessionId(), owner, "/mcp", JUNE,
				generation, integer(32), null, new Target()).status());
		McpLegacySessionStore.Call control = acquire(store, initialization, JUNE, null, null, new Target());
		assertFalse(store.cancel(control, integer(0))); // fresh framing/identity alone is not accepted use
		control.acceptedUse();
		assertTrue(store.cancel(control, integer(0)));
		assertEquals(McpLegacySessionStore.Cause.CLIENT_CANCEL, first.lastCause.get());
		assertFalse(store.cancel(control, integer(1000)));
		calls.get(0).logicalComplete();
		assertEquals(ACCEPTED, store.acquire(initialization.sessionId(), owner, "/mcp", JUNE,
				generation, integer(32), null, new Target()).status());
		assertEquals(35, store.counts().physicalReferences());
	}

	@Test
	void only_a_current_accepted_logical_use_can_retire_a_live_session() {
		for (String revision : List.of(JUNE, NOVEMBER)) {
			McpLegacySessionStore store = store(config(4, 4));
			McpLegacySessionStore.Initialization initialization = publish(store, revision);
			initialization.physicalComplete();
			McpLegacySessionStore.Call unaccepted = acquire(store, initialization, revision, null, null, new Target());
			store.retire(unaccepted, McpLegacySessionStore.Cause.SESSION_CLOSED);
			assertEquals(1, store.counts().liveSessions());
			unaccepted.physicalComplete();
			McpLegacySessionStore.Call logicallyCompleted = acquire(store, initialization, revision, integer(1), null, new Target());
			assertTrue(logicallyCompleted.acceptedUse());
			logicallyCompleted.logicalComplete();
			store.retire(logicallyCompleted, McpLegacySessionStore.Cause.SESSION_CLOSED);
			assertEquals(1, store.counts().liveSessions(), "A lingering worker has lost its logical session authority.");
			McpLegacySessionStore.Call physicallyCompleted = acquire(store, initialization, revision, integer(1), null, new Target());
			assertTrue(physicallyCompleted.acceptedUse());
			physicallyCompleted.physicalComplete();
			store.retire(physicallyCompleted, McpLegacySessionStore.Cause.SESSION_CLOSED);
			assertEquals(1, store.counts().liveSessions(), "Completed handles cannot close a later use of the session.");
			Target currentTarget = new Target();
			McpLegacySessionStore.Call current = acquire(store, initialization, revision, null, null, currentTarget);
			assertTrue(current.acceptedUse());
			store.retire(current, McpLegacySessionStore.Cause.SESSION_CLOSED);
			assertEquals(0, store.counts().liveSessions());
			assertEquals(1, currentTarget.retirements.get());
			store.retire(current, McpLegacySessionStore.Cause.SESSION_CLOSED);
			assertEquals(1, currentTarget.retirements.get());
			assertTrue(store.counts().retainedBytes() > 0);
			current.physicalComplete(); logicallyCompleted.physicalComplete();
			assertEquals(new McpLegacySessionStore.Counts(0, 0, 0, 0, 0), store.counts());
		}
	}

	@Test
	void final_response_and_cancel_race_is_owned_by_the_target_not_a_stale_registry_snapshot() throws Exception {
		McpLegacySessionStore store = store(config(4, 4));
		McpLegacySessionStore.Initialization initialization = publish(store, JUNE);
		CountDownLatch selected = new CountDownLatch(1);
		CountDownLatch continueCancel = new CountDownLatch(1);
		AtomicReference<String> terminal = new AtomicReference<>("open");
		McpLegacySessionStore.Target target = new McpLegacySessionStore.Target() {
			@Override public boolean cancel(McpLegacySessionStore.Cause cause) {
				selected.countDown();
				try { if (!continueCancel.await(2, TimeUnit.SECONDS)) throw new AssertionError("Missing release."); }
				catch (InterruptedException exception) { throw new AssertionError(exception); }
				return terminal.compareAndSet("open", "cancel");
			}
			@Override public void retire(McpLegacySessionStore.Cause cause) { terminal.compareAndSet("open", "retire"); }
		};
		McpLegacySessionStore.Call operation = acquire(store, initialization, JUNE, integer(1), null, target);
		operation.acceptedUse();
		McpLegacySessionStore.Call control = acquire(store, initialization, JUNE, null, null, new Target());
		control.acceptedUse();
		AtomicBoolean won = new AtomicBoolean(true);
		Thread cancellation = new Thread(() -> won.set(store.cancel(control, integer(1))));
		cancellation.start();
		try {
			assertTrue(selected.await(2, TimeUnit.SECONDS));
			assertTrue(terminal.compareAndSet("open", "response"));
			operation.logicalComplete();
		} finally { continueCancel.countDown(); cancellation.join(2000); }
		assertFalse(cancellation.isAlive());
		assertFalse(won.get());
		assertEquals("response", terminal.get());
		store.close();
		assertEquals("response", terminal.get());
		assertTrue(store.counts().retainedBytes() > 0);
		operation.physicalComplete(); control.physicalComplete(); initialization.physicalComplete();
		assertEquals(0, store.counts().retainedBytes());
	}

	@Test
	void retirement_callbacks_can_reenter_from_another_thread_and_one_failure_does_not_skip_others() throws Exception {
		McpLegacySessionStore store = store(config(4, 4));
		McpLegacySessionStore.Initialization initialization = publish(store, JUNE);
		AtomicBoolean otherThreadEntered = new AtomicBoolean();
		McpLegacySessionStore.Target reentrant = new McpLegacySessionStore.Target() {
			@Override public boolean cancel(McpLegacySessionStore.Cause cause) { return false; }
			@Override public void retire(McpLegacySessionStore.Cause cause) {
				Thread other = new Thread(() -> { store.counts(); otherThreadEntered.set(true); });
				other.start();
				try { other.join(1000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
			}
		};
		McpLegacySessionStore.Target throwing = new McpLegacySessionStore.Target() {
			@Override public boolean cancel(McpLegacySessionStore.Cause cause) { throw new IllegalStateException("sensitive"); }
			@Override public void retire(McpLegacySessionStore.Cause cause) { throw new IllegalStateException("sensitive"); }
		};
		McpLegacySessionStore.Call first = acquire(store, initialization, JUNE, integer(1), null, throwing);
		McpLegacySessionStore.Call second = acquire(store, initialization, JUNE, integer(2), null, reentrant);
		store.close();
		assertTrue(otherThreadEntered.get());
		assertEquals(0, store.counts().liveSessions());
		first.physicalComplete(); second.physicalComplete(); initialization.physicalComplete();
		assertEquals(0, store.counts().retainedBytes());
	}

	@Test
	void restart_is_fenced_by_generation_while_old_physical_references_remain_accounted() {
		McpLegacySessionStore store = store(config(4, 4));
		McpLegacySessionStore.Initialization old = publish(store, JUNE);
		long bytes = store.counts().retainedBytes();
		store.close();
		Object newGeneration = new Object();
		McpLegacySessionStore.Initialization fresh = store.publish(owner, "/mcp", JUNE, newGeneration,
				snapshot, new Target()).initialization().orElseThrow();
		assertEquals(bytes * 2, store.counts().retainedBytes());
		assertEquals(NOT_FOUND, store.acquire(old.sessionId(), owner, "/mcp", JUNE, newGeneration,
				integer(1), null, new Target()).status());
		assertEquals(NOT_FOUND, store.acquire(fresh.sessionId(), owner, "/mcp", JUNE, generation,
				integer(1), null, new Target()).status());
		old.physicalComplete();
		assertEquals(bytes, store.counts().retainedBytes());
		store.close(); fresh.physicalComplete();
		assertEquals(0, store.counts().retainedBytes());
	}

	@Test
	void bounded_recreation_churn_keeps_canceled_workers_charged_and_releases_every_residual_reference() {
		McpLegacySessionStore store = store(new McpLegacySessionStore.Config(8, 2,
				120 * SECOND, 300 * SECOND, 65536, false, 1024, 2048, 4096));
		List<McpLegacySessionStore.Call> ignoringWorkers = new ArrayList<>();
		for (int index = 0; index < 2; index++) {
			McpLegacySessionStore.Initialization initialization = publish(store, JUNE);
			McpLegacySessionStore.Call worker = store.acquire(initialization.sessionId(), owner, "/mcp", JUNE,
					generation, integer(1), null, new Target(), 600).call().orElseThrow();
			worker.acceptedUse();
			McpLegacySessionStore.Call cancellation = acquire(store, initialization, JUNE, null, null, new Target());
			cancellation.acceptedUse();
			assertTrue(store.cancel(cancellation, integer(1)));
			worker.logicalComplete(); cancellation.physicalComplete(); initialization.physicalComplete();
			store.close();
			ignoringWorkers.add(worker);
		}
		long baseline = store.counts().retainedBytes();
		assertEquals(2, store.counts().physicalReferences());
		assertEquals(0, store.counts().liveSessions());
		assertTrue(baseline > 1200 && baseline < 2048);
		long peakBytes = baseline;
		int peakPhysical = 2;
		for (int iteration = 0; iteration < 500; iteration++) {
			McpLegacySessionStore.Initialization initialization = store.publish(owner, "/mcp", NOVEMBER,
					generation, snapshot, new Target(), 64).initialization().orElseThrow();
			assertEquals(OWNER_CAPACITY, store.acquire(initialization.sessionId(), owner, "/mcp", NOVEMBER,
						generation, integer(1), null, new Target(), 600).status());
			McpLegacySessionStore.Call shortCall = store.acquire(initialization.sessionId(), owner, "/mcp", NOVEMBER,
						generation, integer(1), new McpProgressToken.StringToken("same-token"), new Target(), 64).call().orElseThrow();
			shortCall.acceptedUse();
			McpLegacySessionStore.Call cancellation = store.acquire(initialization.sessionId(), owner, "/mcp", NOVEMBER,
						generation, null, null, new Target(), 32).call().orElseThrow();
			cancellation.acceptedUse();
			peakBytes = Math.max(peakBytes, store.counts().retainedBytes());
			peakPhysical = Math.max(peakPhysical, store.counts().physicalReferences());
			assertTrue(store.cancel(cancellation, integer(1)));
			shortCall.logicalComplete(); shortCall.physicalComplete(); cancellation.physicalComplete();
			initialization.physicalComplete();
			store.close();
			assertEquals(baseline, store.counts().retainedBytes());
			assertEquals(2, store.counts().physicalReferences());
			assertEquals(0, store.counts().liveSessions());
			assertEquals(1, store.counts().owners());
		}
		assertTrue(peakBytes <= 2048, "Recreation cannot bypass the owner evidence ceiling.");
		assertEquals(5, peakPhysical);
		for (McpLegacySessionStore.Call worker : ignoringWorkers) {
			worker.physicalComplete(); worker.physicalComplete();
		}
		assertEquals(new McpLegacySessionStore.Counts(0, 0, 0, 0, 0), store.counts());
	}

	@Test
	void clock_wrap_and_token_collision_have_bounded_total_cleanup() {
		now.set(Long.MAX_VALUE - 15 * SECOND);
		McpLegacySessionStore store = store(config(4, 4));
		McpLegacySessionStore.Initialization initialization = publish(store, JUNE);
		initialization.physicalComplete();
		now.addAndGet(31 * SECOND);
		store.maintain();
		assertEquals(0, store.counts().retainedBytes());
		assertEquals(0, store.counts().liveSessions());
		AtomicInteger generated = new AtomicInteger();
		McpLegacySessionStore collisions = new McpLegacySessionStore(config(4, 4), McpJsonLimits.productionDefaults(),
				now::get, () -> { generated.incrementAndGet(); return new byte[32]; });
		assertEquals(ACCEPTED, collisions.publish(owner, "/mcp", JUNE, generation, snapshot, new Target()).status());
		assertEquals(INTERNAL_FAILURE, collisions.publish(owner, "/mcp", JUNE, generation, snapshot, new Target()).status());
		assertEquals(17, generated.get());
		assertEquals(1, collisions.counts().liveSessions());
		assertEquals(1, collisions.counts().physicalReferences());
		assertDoesNotThrow(() -> new McpLegacySessionStore.Config(1, 1, Long.MAX_VALUE,
				Long.MAX_VALUE, 1, false, 1, 1, 1));
	}

	private McpLegacySessionStore.Config config(int sessions, int perOwner) {
		return new McpLegacySessionStore.Config(sessions, perOwner, 120 * SECOND,
				300 * SECOND, 65536, false, 1 << 20, 2 << 20, 16 << 20);
	}
	private McpLegacySessionStore store(McpLegacySessionStore.Config config) {
		return new McpLegacySessionStore(config, McpJsonLimits.productionDefaults(), now::get, () -> {
			byte[] bytes = new byte[32];
			ByteBuffer.wrap(bytes).putInt(tokens.incrementAndGet());
			return bytes;
		});
	}
	private McpLegacySessionStore.Initialization publish(McpLegacySessionStore store, String revision) {
		McpLegacySessionStore.Allocation allocation = store.publish(owner, "/mcp", revision, generation, snapshot, new Target());
		assertEquals(ACCEPTED, allocation.status());
		return allocation.initialization().orElseThrow();
	}
	private McpLegacySessionStore.Call acquire(McpLegacySessionStore store,
			McpLegacySessionStore.Initialization initialization, String revision, McpJsonRpcId id,
			McpProgressToken token, McpLegacySessionStore.Target target) {
		McpLegacySessionStore.Acquisition acquisition = store.acquire(initialization.sessionId(), owner,
				"/mcp", revision, generation, id, token, target);
		assertEquals(ACCEPTED, acquisition.status());
		return acquisition.call().orElseThrow();
	}
	private static McpJsonRpcId integer(int value) { return new McpJsonRpcId.IntegerId(BigInteger.valueOf(value)); }
	private static class Target implements McpLegacySessionStore.Target {
		final AtomicInteger retirements = new AtomicInteger();
		final AtomicReference<McpLegacySessionStore.Cause> lastCause = new AtomicReference<>();
		@Override public boolean cancel(McpLegacySessionStore.Cause cause) { lastCause.set(cause); return true; }
		@Override public void retire(McpLegacySessionStore.Cause cause) { retirements.incrementAndGet(); lastCause.set(cause); }
	}
}
