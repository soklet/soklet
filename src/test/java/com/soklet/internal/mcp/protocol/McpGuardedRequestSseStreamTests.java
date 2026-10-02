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

import com.soklet.McpSimulationOptions;
import com.soklet.StreamTerminationReason;
import com.soklet.internal.mcp.transport.McpOutboundChannel;
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpResponse;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import javax.annotation.concurrent.NotThreadSafe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Guarded notification encoding and immediate simulation delivery. */
@NotThreadSafe
@Timeout(30)
public class McpGuardedRequestSseStreamTests {
	private static final McpJsonCodec JSON = new McpJsonCodec(McpJsonLimits.productionDefaults());
	private static final McpJsonRpcEnvelopeCodec ENVELOPES = new McpJsonRpcEnvelopeCodec(JSON);
	private static final List<Mcp2025ProtocolProfile> LEGACY = List.of(
			Mcp2025ProtocolProfile.JUNE_18, Mcp2025ProtocolProfile.NOVEMBER_25);

	@Test
	public void simulator_revalidates_at_capture_and_releases_each_offer_once_for_both_profiles() throws Exception {
		for (Mcp2025ProtocolProfile profile : LEGACY) {
			McpSimulationRuntime runtime = new McpSimulationRuntime(McpSimulationOptions.builder()
					.streamItemQueueCapacity(8).maximumCapturedSizeInBytes(4096).build(), () -> {});
			runtime.openChannel((reason, observation, cause) -> {});
			McpRequestSseStream stream = new McpRequestSseStream(ENVELOPES, JSON, profile, runtime);
			runtime.acceptResponse(stream.response(List.of()));
			AtomicBoolean allowed = new AtomicBoolean(false);
			AtomicInteger releases = new AtomicInteger();
			var lockField = McpSimulationRuntime.class.getDeclaredField("lock");
			lockField.setAccessible(true);
			Object lock = lockField.get(runtime);
			Runnable release = () -> {
				Assertions.assertFalse(Thread.holdsLock(lock));
				releases.incrementAndGet();
			};
			McpJsonRpcMessage.Notification message = notification();
			Assertions.assertEquals(Optional.empty(), stream.offerGuardedCoalescingMessage(
					message, "catalog", allowed::get, release));
			Assertions.assertTrue(runtime.awaitStreamItem(Duration.ZERO).isEmpty());
			allowed.set(true);
			Assertions.assertEquals(Optional.of(McpOutboundChannel.OfferResult.ACCEPTED),
					stream.offerGuardedCoalescingMessage(message, "catalog", allowed::get, release));
			Assertions.assertEquals(Optional.of(McpOutboundChannel.OfferResult.COALESCED),
					stream.offerGuardedCoalescingMessage(message, "catalog", allowed::get, release));
			Assertions.assertEquals(3, releases.get());
			allowed.set(false);
			Assertions.assertTrue(stream.recheckGuardedFrames());
			var captured = runtime.awaitStreamItem(Duration.ZERO).orElseThrow();
			Assertions.assertEquals("data: " + new String(ENVELOPES.encode(message), StandardCharsets.UTF_8) + "\n\n",
					new String(captured.getEncodedBytes(), StandardCharsets.UTF_8));
			Assertions.assertTrue(runtime.awaitStreamItem(Duration.ZERO).isEmpty());
			stream.close(StreamTerminationReason.SERVER_STOPPING, null);
			Assertions.assertFalse(stream.recheckGuardedFrames());
			Assertions.assertEquals(3, releases.get());
		}
	}

	@Test
	public void revocation_after_encoding_is_observed_at_the_capture_boundary() {
		RecordingChannel channel = new RecordingChannel();
		McpRequestSseStream stream = new McpRequestSseStream(ENVELOPES, JSON,
				Mcp2025ProtocolProfile.NOVEMBER_25, channel);
		AtomicBoolean allowed = new AtomicBoolean(true);
		AtomicInteger releases = new AtomicInteger();
		McpRequestSseStream.setTestHooks(new McpRequestSseStream.TestHooks() {
			@Override public void beforeTerminalReservation() {}
			@Override public void beforeCoalescingMessageOffer() { allowed.set(false); }
		});
		try {
			Assertions.assertEquals(Optional.empty(), stream.offerGuardedCoalescingMessage(
					notification(), "catalog", allowed::get, releases::incrementAndGet));
			Assertions.assertTrue(channel.frames.isEmpty());
			Assertions.assertEquals(1, releases.get());
		} finally {
			McpRequestSseStream.setTestHooks(null);
		}
	}

	@Test
	public void encoding_failure_releases_before_channel_handoff() {
		for (Mcp2025ProtocolProfile profile : LEGACY) {
			RecordingChannel channel = new RecordingChannel();
			McpRequestSseStream stream = new McpRequestSseStream(ENVELOPES, JSON, profile, channel);
			McpJsonRpcMessage.Request unsupported = new McpJsonRpcMessage.Request(
					new McpJsonRpcId.StringId("server"), "elicitation/create",
					new McpRequestParameters(McpRequestMetadata.fromClientCapabilities(
							Mcp20260728ProtocolProfile.INSTANCE, McpClientCapabilities.empty()), McpJsonObject.empty()),
					McpJsonObject.empty());
			AtomicInteger releases = new AtomicInteger();
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> stream.offerGuardedCoalescingMessage(unsupported, "server", () -> true, releases::incrementAndGet));
			Assertions.assertTrue(channel.frames.isEmpty());
			Assertions.assertEquals(1, releases.get());
		}
	}

	@Test
	public void immediate_capture_default_preserves_exact_notification_bytes_and_releases_after_capture() {
		RecordingChannel channel = new RecordingChannel();
		McpRequestSseStream stream = new McpRequestSseStream(ENVELOPES, channel);
		AtomicInteger releases = new AtomicInteger();
		Assertions.assertEquals(Optional.of(McpOutboundChannel.OfferResult.ACCEPTED),
				stream.offerGuardedCoalescingMessage(notification(), "catalog", () -> true, () -> {
					Assertions.assertEquals(1, channel.frames.size());
					releases.incrementAndGet();
				}));
		Assertions.assertEquals(1, releases.get());
		Assertions.assertEquals("data: " + new String(ENVELOPES.encode(notification()), StandardCharsets.UTF_8) + "\n\n",
				new String(channel.frames.get(0).encodedBytes(), StandardCharsets.UTF_8));
	}

	private static McpJsonRpcMessage.Notification notification() {
		return new McpJsonRpcMessage.Notification("notifications/resources/list_changed",
				Optional.empty(), McpJsonObject.empty());
	}
	private static final class RecordingChannel implements McpRequestSseStream.Channel {
		private final List<McpRequestSseStream.Frame> frames = new ArrayList<>();
		@Override public @NonNull MicrohttpResponse response(@NonNull List<@NonNull Header> headers) {
			throw new AssertionError("Encoding tests do not commit HTTP responses.");
		}
		@Override public boolean enqueue(McpRequestSseStream.@NonNull Frame frame) {
			frames.add(frame); return true;
		}
		@Override public McpOutboundChannel.@NonNull OfferResult offer(McpRequestSseStream.@NonNull Frame frame) {
			frames.add(frame); return McpOutboundChannel.OfferResult.ACCEPTED;
		}
		@Override public McpOutboundChannel.@NonNull OfferResult offerCoalescing(
				McpRequestSseStream.@NonNull Frame frame, @NonNull Object coalescingKey) {
			return offer(frame);
		}
		@Override public @NonNull Optional<McpOutboundChannel.@NonNull OfferResult> offerCoalescingIf(
				McpRequestSseStream.@NonNull Frame frame, @NonNull Object coalescingKey,
				@NonNull BooleanSupplier offerAllowed) {
			return offerAllowed.getAsBoolean() ? Optional.of(offer(frame)) : Optional.empty();
		}
		@Override public boolean complete(McpRequestSseStream.@NonNull Frame terminalFrame) {
			frames.add(terminalFrame); return true;
		}
		@Override public boolean completeWithoutMessage(boolean discardUncommittedMessages) {
			if (discardUncommittedMessages) frames.clear();
			return true;
		}
		@Override public boolean fail(@NonNull StreamTerminationReason reason, @Nullable Throwable cause) { return true; }
		@Override public boolean failIfDeadlineExpired(long nowNanos, long deadlineNanos,
				@NonNull StreamTerminationReason reason, @Nullable Throwable cause) { return true; }
		@Override public boolean failIfWriteIdleExpired(long nowNanos, long timeoutNanos,
				@NonNull StreamTerminationReason reason, @Nullable Throwable cause) { return true; }
		@Override public long responseWriteIdleDeadlineNanos(long timeoutNanos) { return Long.MAX_VALUE; }
		@Override public void close(@NonNull StreamTerminationReason reason, @Nullable Throwable cause) { }
		@Override public @NonNull Optional<McpOutboundChannel.@NonNull Snapshot> snapshot() { return Optional.empty(); }
		@Override public boolean isTerminalWritten() { return false; }
	}
}
