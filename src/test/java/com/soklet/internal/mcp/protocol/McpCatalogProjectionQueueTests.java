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

package com.soklet.internal.mcp.protocol;

import com.soklet.StreamTerminationReason;
import com.soklet.internal.mcp.transport.McpOutboundChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.soklet.internal.mcp.protocol.McpCatalogProjectionQueue.Family.PROMPTS;
import static com.soklet.internal.mcp.protocol.McpCatalogProjectionQueue.Family.TOOLS;
import static com.soklet.internal.mcp.protocol.McpCatalogProjectionQueue.RequestResult.COALESCED;
import static com.soklet.internal.mcp.protocol.McpCatalogProjectionQueue.RequestResult.SUBMIT;
import static java.util.Objects.requireNonNull;

public class McpCatalogProjectionQueueTests {
	private static final long FIRST_DEADLINE_NANOS = 101L;
	private static final long LATER_DEADLINE_NANOS = 202L;

	@Test
	public void digestIsExactlyThirtyTwoBytesDefensiveAndValueBased() {
		byte[] source = bytes(7);
		McpCatalogProjectionQueue.Digest digest = digest(source);
		McpCatalogProjectionQueue.Digest equal = digest(bytes(7));
		McpCatalogProjectionQueue.Digest different = digest(bytes(8));

		source[0] ^= 0x7F;
		byte[] exposed = digest.bytes();
		exposed[1] ^= 0x7F;

		Assertions.assertEquals(equal, digest);
		Assertions.assertEquals(equal.hashCode(), digest.hashCode());
		Assertions.assertNotEquals(different, digest);
		Assertions.assertEquals("Digest{}", digest.toString());
		Assertions.assertArrayEquals(bytes(7), digest.bytes());
		Assertions.assertThrows(NullPointerException.class,
				() -> new McpCatalogProjectionQueue.Digest(null));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> digest(new byte[31]));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> digest(new byte[33]));
	}

	@Test
	public void oneOwnerJobCoalescesGenerationsAndAdvancesOnlyItsBaseline() {
		McpCatalogProjectionQueue queue = new McpCatalogProjectionQueue();
		McpCatalogProjectionQueue.Digest first = digest(bytes(1));
		McpCatalogProjectionQueue.Digest second = digest(bytes(2));
		queue.establishBaseline(TOOLS, first);

		Assertions.assertEquals(SUBMIT,
				queue.request(TOOLS, FIRST_DEADLINE_NANOS));
		Assertions.assertEquals(COALESCED,
				queue.request(TOOLS, LATER_DEADLINE_NANOS));
		McpCatalogProjectionQueue.Projection projection = projection(queue);
		Assertions.assertEquals(TOOLS, projection.family());
		Assertions.assertEquals(2L, projection.generation());
		Assertions.assertEquals(FIRST_DEADLINE_NANOS,
				projection.deadlineNanos(),
				"Coalescing must not extend a dirty family's deadline.");
		Assertions.assertEquals(first, projection.baseline());
		Assertions.assertTrue(queue.owns(projection));

		queue.advanceBaseline(projection, second);
		Assertions.assertFalse(queue.finish(projection, true, true));
		Assertions.assertFalse(queue.owns(projection));

		Assertions.assertEquals(SUBMIT,
				queue.request(TOOLS, LATER_DEADLINE_NANOS));
		McpCatalogProjectionQueue.Projection next = projection(queue);
		Assertions.assertEquals(3L, next.generation());
		Assertions.assertEquals(LATER_DEADLINE_NANOS,
				next.deadlineNanos(),
				"A clean family must capture its next dirty epoch's deadline.");
		Assertions.assertEquals(second, next.baseline());
		Assertions.assertFalse(queue.finish(next, true, true));
	}

	@Test
	public void unwrittenGuardedHintRestoresBaselineForAllowedReconciliation() {
		McpCatalogProjectionQueue queue = new McpCatalogProjectionQueue();
		McpCatalogProjectionQueue.Digest initial = digest(bytes(1)), changed = digest(bytes(2));
		queue.establishBaseline(TOOLS, initial);
		Assertions.assertEquals(SUBMIT, queue.request(TOOLS, FIRST_DEADLINE_NANOS));
		McpCatalogProjectionQueue.Projection offered = projection(queue);
		McpOutboundChannel channel = new McpOutboundChannel(2, 64, 64, System::nanoTime, new McpOutboundChannel.Listener() {
			@Override public void didWrite(long bytes, long timestamp) {}
			@Override public void didApplyBackpressure() {}
			@Override public void didTerminate(StreamTerminationReason reason, Throwable cause) {}
		});
		AtomicBoolean authorized = new AtomicBoolean(true), written = new AtomicBoolean();
		Assertions.assertEquals(Optional.of(McpOutboundChannel.OfferResult.ACCEPTED), channel.offerGuardedCoalescing(
				new byte[]{1}, "tools", authorized::get, () -> written.set(true),
				() -> { if (!written.get()) queue.invalidateUnwrittenHint(offered); }));
		queue.advanceBaseline(offered, changed);
		Assertions.assertFalse(queue.finish(offered, true, true));
		authorized.set(false);
		Assertions.assertTrue(channel.recheckGuardedFrames());
		Assertions.assertEquals(0, channel.snapshot().bufferedFrames());
		Assertions.assertEquals(SUBMIT, queue.retryAll(LATER_DEADLINE_NANOS));
		McpCatalogProjectionQueue.Projection afterReconciliation = projection(queue);
		Assertions.assertEquals(initial, afterReconciliation.baseline(),
				"An allowed reconciliation must project the change whose hint was purged before writing.");
		Assertions.assertNotEquals(changed, afterReconciliation.baseline());
		queue.advanceBaseline(afterReconciliation, changed);
		Assertions.assertFalse(queue.finish(afterReconciliation, true, true));
		Assertions.assertFalse(queue.invalidateUnwrittenHint(offered),
				"Release of an older coalesced hint must not roll back its replacement.");
	}

	@Test
	public void coalescedPayloadReleaseKeepsCoverageUntilTheRetainedFrameIsPurged() {
		McpCatalogProjectionQueue queue = new McpCatalogProjectionQueue();
		McpCatalogProjectionQueue.Digest initial = digest(bytes(1)), firstChange = digest(bytes(2)), secondChange = digest(bytes(3));
		queue.establishBaseline(TOOLS, initial);
		queue.request(TOOLS, FIRST_DEADLINE_NANOS);
		McpCatalogProjectionQueue.Projection first = projection(queue);
		Object retainedFrame = new Object(), incomingOffer = new Object();
		AtomicBoolean authorized = new AtomicBoolean(true);
		McpOutboundChannel channel = new McpOutboundChannel(2, 64, 64, System::nanoTime, new McpOutboundChannel.Listener() {
			@Override public void didWrite(long bytes, long timestamp) {}
			@Override public void didApplyBackpressure() {}
			@Override public void didTerminate(StreamTerminationReason reason, Throwable cause) {}
		});
		Assertions.assertEquals(Optional.of(McpOutboundChannel.OfferResult.ACCEPTED), channel.offerGuardedCoalescing(
				new byte[]{1}, "tools", authorized::get,
				() -> queue.invalidateUnwrittenHint(TOOLS, retainedFrame, initial)));
		queue.advanceBaseline(first, firstChange, retainedFrame);
		queue.finish(first, true, true);
		queue.request(TOOLS, LATER_DEADLINE_NANOS);
		McpCatalogProjectionQueue.Projection second = projection(queue);
		Assertions.assertEquals(Optional.of(McpOutboundChannel.OfferResult.COALESCED), channel.offerGuardedCoalescing(
				new byte[]{1}, "tools", authorized::get,
				() -> Assertions.assertFalse(queue.invalidateUnwrittenHint(TOOLS, incomingOffer, firstChange))));
		queue.advanceBaseline(second, secondChange, retainedFrame);
		queue.finish(second, true, true);
		queue.retryAll(LATER_DEADLINE_NANOS + 1L);
		McpCatalogProjectionQueue.Projection unchanged = projection(queue);
		Assertions.assertEquals(secondChange, unchanged.baseline(), "Discarding the coalesced offer must not undo its covered baseline.");
		queue.finish(unchanged, true, true);
		authorized.set(false);
		Assertions.assertTrue(channel.recheckGuardedFrames());
		queue.retryAll(LATER_DEADLINE_NANOS + 2L);
		McpCatalogProjectionQueue.Projection afterFence = projection(queue);
		Assertions.assertEquals(initial, afterFence.baseline(),
				"Purging the retained frame must undo all changes it covered, including coalesced generations.");
		queue.finish(afterFence, true, true);
	}

	@Test
	public void successorStartedBeforePurgeRetainsLastUnwrittenFrameBaseline() {
		McpCatalogProjectionQueue queue = new McpCatalogProjectionQueue();
		McpCatalogProjectionQueue.Digest initial = digest(bytes(1)), firstChange = digest(bytes(2)), secondChange = digest(bytes(3));
		queue.establishBaseline(TOOLS, initial);
		queue.request(TOOLS, FIRST_DEADLINE_NANOS);
		McpCatalogProjectionQueue.Projection first = projection(queue);
		McpHttpServerRuntime.CatalogHintDelivery firstFrame = new McpHttpServerRuntime.CatalogHintDelivery(first);
		AtomicBoolean oldGeneration = new AtomicBoolean(true), newGeneration = new AtomicBoolean(true);
		McpOutboundChannel channel = new McpOutboundChannel(2, 64, 64, System::nanoTime, new McpOutboundChannel.Listener() {
			@Override public void didWrite(long bytes, long timestamp) {}
			@Override public void didApplyBackpressure() {}
			@Override public void didTerminate(StreamTerminationReason reason, Throwable cause) {}
		});
		channel.offerGuardedCoalescing(new byte[]{1}, "tools", oldGeneration::get, () -> {
			if (queue.invalidateUnwrittenHint(TOOLS, firstFrame, firstFrame.previousBaseline()))
				queue.request(TOOLS, LATER_DEADLINE_NANOS);
		});
		queue.advanceBaseline(first, firstChange, firstFrame);
		queue.finish(first, true, true);
		// The accepted authorization schedules its refresh before purging old frames.
		queue.request(TOOLS, LATER_DEADLINE_NANOS);
		McpCatalogProjectionQueue.Projection successor = projection(queue);
		McpHttpServerRuntime.CatalogHintDelivery successorFrame = new McpHttpServerRuntime.CatalogHintDelivery(successor);
		Assertions.assertEquals(firstChange, successor.baseline());
		oldGeneration.set(false);
		Assertions.assertTrue(channel.recheckGuardedFrames());
		// The projection remains authorized under its newer generation. Its snapshot
		// includes the first offer, but the client has received neither invalidation.
		successorFrame.inheritUnwrittenBaselineFrom(firstFrame);
		Assertions.assertEquals(initial, successorFrame.previousBaseline());
		channel.offerGuardedCoalescing(new byte[]{2}, "tools", newGeneration::get,
				() -> queue.invalidateUnwrittenHint(TOOLS, successorFrame, successorFrame.previousBaseline()));
		queue.advanceBaseline(successor, secondChange, successorFrame);
		Assertions.assertTrue(queue.finish(successor, true, true));
		McpCatalogProjectionQueue.Projection catchUp = projection(queue);
		Assertions.assertEquals(secondChange, catchUp.baseline());
		queue.finish(catchUp, true, true);
		newGeneration.set(false);
		Assertions.assertTrue(channel.recheckGuardedFrames());
		queue.retryAll(LATER_DEADLINE_NANOS + 1L);
		McpCatalogProjectionQueue.Projection reauthorized = projection(queue);
		Assertions.assertEquals(initial, reauthorized.baseline());
		Assertions.assertNotEquals(firstChange, reauthorized.baseline(),
				"Returning to the first changed catalog must notify a client which still holds the initial catalog.");
		queue.finish(reauthorized, true, true);
	}

	@Test
	public void predecessorWriteCompletionCannotUndoInheritedConservativeBaseline() {
		McpCatalogProjectionQueue queue = new McpCatalogProjectionQueue();
		McpCatalogProjectionQueue.Digest initial = digest(bytes(1)), changed = digest(bytes(2));
		queue.establishBaseline(TOOLS, initial);
		queue.request(TOOLS, FIRST_DEADLINE_NANOS);
		McpCatalogProjectionQueue.Projection first = projection(queue);
		McpHttpServerRuntime.CatalogHintDelivery preceding = new McpHttpServerRuntime.CatalogHintDelivery(first);
		queue.advanceBaseline(first, changed, preceding);
		queue.finish(first, true, true);
		queue.request(TOOLS, LATER_DEADLINE_NANOS);
		McpCatalogProjectionQueue.Projection next = projection(queue);
		McpHttpServerRuntime.CatalogHintDelivery conservative = new McpHttpServerRuntime.CatalogHintDelivery(next);
		conservative.inheritUnwrittenBaselineFrom(preceding);
		preceding.markWritten();
		Assertions.assertEquals(initial, conservative.previousBaseline(),
				"Write completion after inheritance may cause an extra hint, never stale suppression.");
		McpHttpServerRuntime.CatalogHintDelivery afterWrite = new McpHttpServerRuntime.CatalogHintDelivery(next);
		afterWrite.inheritUnwrittenBaselineFrom(preceding);
		Assertions.assertEquals(changed, afterWrite.previousBaseline());
		queue.finish(next, true, true);
	}

	@Test
	public void familiesRetainRequestOrderAndActiveRerunsReturnAtTheTail() {
		McpCatalogProjectionQueue queue = establishedQueue();
		Assertions.assertEquals(SUBMIT,
				queue.request(PROMPTS, FIRST_DEADLINE_NANOS));
		Assertions.assertEquals(COALESCED,
				queue.request(TOOLS, FIRST_DEADLINE_NANOS));

		McpCatalogProjectionQueue.Projection prompts = projection(queue);
		Assertions.assertEquals(PROMPTS, prompts.family());
		Assertions.assertEquals(COALESCED,
				queue.request(PROMPTS, LATER_DEADLINE_NANOS));
		Assertions.assertTrue(queue.finish(prompts, true, true));

		McpCatalogProjectionQueue.Projection tools = projection(queue);
		Assertions.assertEquals(TOOLS, tools.family());
		Assertions.assertTrue(queue.finish(tools, true, true));

		McpCatalogProjectionQueue.Projection promptsAgain = projection(queue);
		Assertions.assertEquals(PROMPTS, promptsAgain.family());
		Assertions.assertEquals(2L, promptsAgain.generation());
		Assertions.assertEquals(FIRST_DEADLINE_NANOS,
				promptsAgain.deadlineNanos());
		Assertions.assertFalse(queue.finish(promptsAgain, true, true));
	}

	@Test
	public void failedGenerationWaitsForRetryAndRetryAllUsesEnumOrder() {
		McpCatalogProjectionQueue queue = establishedQueue();
		Assertions.assertEquals(SUBMIT,
				queue.request(PROMPTS, FIRST_DEADLINE_NANOS));
		McpCatalogProjectionQueue.Projection failed = projection(queue);
		Assertions.assertFalse(queue.finish(failed, true, false),
				"A failed generation must not create a hot retry loop.");

		Assertions.assertEquals(SUBMIT,
				queue.retryAll(LATER_DEADLINE_NANOS));
		Assertions.assertEquals(COALESCED,
				queue.retryAll(LATER_DEADLINE_NANOS + 1L));
		McpCatalogProjectionQueue.Projection tools = projection(queue);
		Assertions.assertEquals(TOOLS, tools.family());
		Assertions.assertEquals(2L, tools.generation());
		Assertions.assertEquals(LATER_DEADLINE_NANOS,
				tools.deadlineNanos());
		Assertions.assertTrue(queue.finish(tools, true, true));
		McpCatalogProjectionQueue.Projection prompts = projection(queue);
		Assertions.assertEquals(PROMPTS, prompts.family());
		Assertions.assertEquals(3L, prompts.generation());
		Assertions.assertEquals(LATER_DEADLINE_NANOS,
				prompts.deadlineNanos(),
				"A signal after failed work must start a fresh deadline.");
		Assertions.assertEquals(digest(bytes(2)), prompts.baseline(),
				"Failed work must retain the last accepted baseline.");
		Assertions.assertFalse(queue.finish(prompts, true, true));
	}

	@Test
	public void signalAfterFailureRefreshesButPendingSignalsPreserveDeadline() {
		McpCatalogProjectionQueue queue = new McpCatalogProjectionQueue();
		queue.establishBaseline(TOOLS, digest(bytes(1)));
		Assertions.assertEquals(SUBMIT,
				queue.request(TOOLS, FIRST_DEADLINE_NANOS));
		McpCatalogProjectionQueue.Projection failed = projection(queue);
		Assertions.assertFalse(queue.finish(failed, true, false));

		Assertions.assertEquals(SUBMIT,
				queue.request(TOOLS, LATER_DEADLINE_NANOS));
		Assertions.assertEquals(COALESCED,
				queue.request(TOOLS, LATER_DEADLINE_NANOS + 1L));
		McpCatalogProjectionQueue.Projection retry = projection(queue);
		Assertions.assertEquals(LATER_DEADLINE_NANOS,
				retry.deadlineNanos());
		Assertions.assertEquals(3L, retry.generation());
		Assertions.assertFalse(queue.finish(retry, true, true));
	}

	@Test
	public void deferredSchedulerReservationPreservesDirtyWorkAndDeadline() {
		McpCatalogProjectionQueue queue = new McpCatalogProjectionQueue();
		McpCatalogProjectionQueue.Digest baseline = digest(bytes(1));
		queue.establishBaseline(TOOLS, baseline);
		Assertions.assertEquals(SUBMIT,
				queue.request(TOOLS, FIRST_DEADLINE_NANOS));

		queue.deferOutstandingJob();
		Assertions.assertFalse(queue.jobOutstanding());
		Assertions.assertEquals(SUBMIT,
				queue.retryAll(LATER_DEADLINE_NANOS));
		McpCatalogProjectionQueue.Projection projection = projection(queue);
		Assertions.assertEquals(TOOLS, projection.family());
		Assertions.assertEquals(2L, projection.generation());
		Assertions.assertEquals(LATER_DEADLINE_NANOS,
				projection.deadlineNanos(),
				"Fresh authorization must start a new projection deadline.");
		Assertions.assertEquals(baseline, projection.baseline());
		Assertions.assertFalse(queue.finish(projection, true, true));
	}

	@Test
	public void newerGenerationRequeuesEvenWhenTheActiveProjectionFailed() {
		McpCatalogProjectionQueue queue = new McpCatalogProjectionQueue();
		queue.establishBaseline(TOOLS, digest(bytes(1)));
		Assertions.assertEquals(SUBMIT,
				queue.request(TOOLS, FIRST_DEADLINE_NANOS));
		McpCatalogProjectionQueue.Projection first = projection(queue);
		Assertions.assertEquals(COALESCED,
				queue.request(TOOLS, LATER_DEADLINE_NANOS));

		Assertions.assertTrue(queue.finish(first, true, false));
		McpCatalogProjectionQueue.Projection newer = projection(queue);
		Assertions.assertEquals(2L, newer.generation());
		Assertions.assertEquals(FIRST_DEADLINE_NANOS,
				newer.deadlineNanos());
		Assertions.assertFalse(queue.finish(newer, true, true));
	}

	@Test
	public void invalidationAfterActiveTimeoutStartsANewFixedDeadline() {
		McpCatalogProjectionQueue queue = new McpCatalogProjectionQueue();
		queue.establishBaseline(TOOLS, digest(bytes(1)));
		Assertions.assertEquals(SUBMIT,
				queue.request(TOOLS, FIRST_DEADLINE_NANOS));
		McpCatalogProjectionQueue.Projection timedOut = projection(queue);
		queue.markActiveCompletionDeferred(timedOut);

		Assertions.assertEquals(COALESCED,
				queue.request(TOOLS, LATER_DEADLINE_NANOS));
		Assertions.assertTrue(queue.finish(timedOut, true, false));
		McpCatalogProjectionQueue.Projection successor = projection(queue);
		Assertions.assertEquals(2L, successor.generation());
		Assertions.assertEquals(LATER_DEADLINE_NANOS,
				successor.deadlineNanos());
		Assertions.assertFalse(queue.finish(successor, true, true));
	}

	@Test
	public void invalidationBeforeTimeoutRetainsItsDeadlineForDeferredSuccessor() {
		McpCatalogProjectionQueue queue = new McpCatalogProjectionQueue();
		queue.establishBaseline(TOOLS, digest(bytes(1)));
		Assertions.assertEquals(SUBMIT,
				queue.request(TOOLS, FIRST_DEADLINE_NANOS));
		McpCatalogProjectionQueue.Projection timedOut = projection(queue);
		Assertions.assertEquals(COALESCED,
				queue.request(TOOLS, LATER_DEADLINE_NANOS));

		queue.markActiveCompletionDeferred(timedOut);
		Assertions.assertTrue(queue.finish(timedOut, true, false));
		McpCatalogProjectionQueue.Projection successor = projection(queue);
		Assertions.assertEquals(LATER_DEADLINE_NANOS,
				successor.deadlineNanos());
		Assertions.assertFalse(queue.finish(successor, true, true));
	}

	@Test
	public void authorizationRefreshReplacesEveryPendingFamilyDeadline() {
		McpCatalogProjectionQueue queue = establishedQueue();
		Assertions.assertEquals(SUBMIT,
				queue.request(TOOLS, FIRST_DEADLINE_NANOS));
		Assertions.assertEquals(COALESCED,
				queue.request(PROMPTS, FIRST_DEADLINE_NANOS));
		McpCatalogProjectionQueue.Projection active = projection(queue);
		Assertions.assertEquals(TOOLS, active.family());

		queue.markAuthorizationChanged();
		Assertions.assertEquals(COALESCED,
				queue.retryAll(LATER_DEADLINE_NANOS));
		Assertions.assertTrue(queue.finish(active, true, false));
		McpCatalogProjectionQueue.Projection prompts = projection(queue);
		Assertions.assertEquals(PROMPTS, prompts.family());
		Assertions.assertEquals(LATER_DEADLINE_NANOS,
				prompts.deadlineNanos());
		Assertions.assertTrue(queue.finish(prompts, true, true));
		McpCatalogProjectionQueue.Projection tools = projection(queue);
		Assertions.assertEquals(TOOLS, tools.family());
		Assertions.assertEquals(LATER_DEADLINE_NANOS,
				tools.deadlineNanos());
		Assertions.assertFalse(queue.finish(tools, true, true));
	}

	@Test
	public void resetAndInactiveFinishFenceLateWorkersAndClearBaselines() {
		McpCatalogProjectionQueue queue = establishedQueue();
		Assertions.assertEquals(SUBMIT,
				queue.request(TOOLS, FIRST_DEADLINE_NANOS));
		McpCatalogProjectionQueue.Projection old = projection(queue);
		Assertions.assertEquals(COALESCED,
				queue.request(PROMPTS, FIRST_DEADLINE_NANOS));

		Assertions.assertFalse(queue.finish(old, false, false));
		Assertions.assertFalse(queue.owns(old));
		Assertions.assertEquals(COALESCED,
				queue.retryAll(LATER_DEADLINE_NANOS));
		Assertions.assertThrows(IllegalStateException.class,
				() -> queue.request(TOOLS, LATER_DEADLINE_NANOS));
		Assertions.assertThrows(IllegalStateException.class,
				() -> queue.advanceBaseline(old, digest(bytes(9))));

		queue.establishBaseline(TOOLS, digest(bytes(3)));
		Assertions.assertEquals(SUBMIT,
				queue.request(TOOLS, LATER_DEADLINE_NANOS));
		McpCatalogProjectionQueue.Projection current = projection(queue);
		Assertions.assertEquals(LATER_DEADLINE_NANOS,
				current.deadlineNanos(),
				"Reset must clear a previous dirty epoch's deadline.");
		Assertions.assertFalse(queue.finish(old, true, true));
		Assertions.assertTrue(queue.owns(current),
				"A late worker must not clear a newer active projection.");
		queue.reset();
		Assertions.assertFalse(queue.owns(current));
	}

	@Test
	public void invalidTransitionsFailAtomically() {
		McpCatalogProjectionQueue queue = new McpCatalogProjectionQueue();
		McpCatalogProjectionQueue.Digest baseline = digest(bytes(1));

		Assertions.assertThrows(NullPointerException.class,
				() -> queue.establishBaseline(null, baseline));
		Assertions.assertThrows(NullPointerException.class,
				() -> queue.establishBaseline(TOOLS, null));
		Assertions.assertThrows(IllegalStateException.class,
				() -> queue.request(TOOLS, FIRST_DEADLINE_NANOS));
		Assertions.assertThrows(IllegalStateException.class, queue::poll);
		Assertions.assertThrows(IllegalStateException.class,
				queue::deferOutstandingJob);

		queue.establishBaseline(TOOLS, baseline);
		Assertions.assertThrows(IllegalStateException.class,
				() -> queue.establishBaseline(TOOLS, baseline));
		Assertions.assertEquals(SUBMIT,
				queue.request(TOOLS, FIRST_DEADLINE_NANOS));
		McpCatalogProjectionQueue.Projection projection = projection(queue);
		Assertions.assertThrows(IllegalStateException.class, queue::poll);
		Assertions.assertThrows(IllegalStateException.class,
				queue::deferOutstandingJob);
		Assertions.assertThrows(NullPointerException.class,
				() -> queue.advanceBaseline(projection, null));
		McpCatalogProjectionQueue other = establishedQueue();
		Assertions.assertEquals(SUBMIT,
				other.request(TOOLS, LATER_DEADLINE_NANOS));
		Assertions.assertThrows(IllegalStateException.class,
				() -> queue.markActiveCompletionDeferred(projection(other)));
		Assertions.assertTrue(queue.owns(projection),
				"A failed baseline update must retain projection ownership.");
	}

	private static McpCatalogProjectionQueue establishedQueue() {
		McpCatalogProjectionQueue queue = new McpCatalogProjectionQueue();
		queue.establishBaseline(TOOLS, digest(bytes(1)));
		queue.establishBaseline(PROMPTS, digest(bytes(2)));
		return queue;
	}

	private static McpCatalogProjectionQueue.Projection projection(
			McpCatalogProjectionQueue queue) {
		return requireNonNull(queue.poll());
	}

	private static McpCatalogProjectionQueue.Digest digest(byte[] bytes) {
		return new McpCatalogProjectionQueue.Digest(bytes);
	}

	private static byte[] bytes(int value) {
		byte[] bytes = new byte[32];
		Arrays.fill(bytes, (byte) value);
		return bytes;
	}
}
