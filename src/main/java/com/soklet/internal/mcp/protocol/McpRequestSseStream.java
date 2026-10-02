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

import com.soklet.StreamTerminationReason;
import com.soklet.internal.mcp.transport.McpOutboundChannel;
import com.soklet.McpStreamTerminationReason;
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpResponse;
import com.soklet.internal.microhttp.StreamingMicrohttpResponses;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static java.util.Objects.requireNonNull;

/**
 * One lazily committed, request-scoped MCP SSE response. JSON-RPC messages
 * use the default SSE message event through a {@code data} field; HTTP chunk
 * framing remains the responsibility of {@link McpOutboundChannel}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpRequestSseStream {
	enum FrameType {
		JSON_MESSAGE,
		KEEP_ALIVE_COMMENT
	}

	record Frame(@NonNull FrameType type,
			@Nullable McpJsonRpcMessage message,
			@Nullable McpJsonObject jsonMessage, byte @NonNull [] encodedBytes) {
		Frame(@NonNull FrameType type, @Nullable McpJsonRpcMessage message,
				byte @NonNull [] encodedBytes) {
			this(type, message, message == null ? null : message.toJsonObject(), encodedBytes);
		}

		Frame {
			requireNonNull(type);
			encodedBytes = Arrays.copyOf(requireNonNull(encodedBytes),
					encodedBytes.length);
			if ((type == FrameType.JSON_MESSAGE) != (message != null)
					|| (type == FrameType.JSON_MESSAGE) != (jsonMessage != null))
				throw new IllegalArgumentException(
						"Only JSON-message frames carry an MCP message.");
			if (encodedBytes.length == 0)
				throw new IllegalArgumentException("SSE frames must not be empty.");
		}

		@Override
		public byte @NonNull [] encodedBytes() {
			return Arrays.copyOf(this.encodedBytes, this.encodedBytes.length);
		}
	}

	interface Channel {
		@NonNull
		MicrohttpResponse response(@NonNull List<@NonNull Header> headers);

		boolean enqueue(@NonNull Frame frame) throws InterruptedException;

		McpOutboundChannel.@NonNull OfferResult offer(@NonNull Frame frame);

		McpOutboundChannel.@NonNull OfferResult offerCoalescing(
				@NonNull Frame frame, @NonNull Object coalescingKey);

		@NonNull
		Optional<McpOutboundChannel.@NonNull OfferResult> offerCoalescingIf(
				@NonNull Frame frame, @NonNull Object coalescingKey,
				@NonNull BooleanSupplier offerAllowed);

		/** Immediate-capture fallback; the socket channel overrides this guard. */
		@NonNull
		default Optional<McpOutboundChannel.@NonNull OfferResult> offerGuardedCoalescing(
				@NonNull Frame frame, @NonNull Object coalescingKey,
				@NonNull BooleanSupplier writeAllowed, @NonNull Runnable payloadReleased) {
			requireNonNull(payloadReleased);
			try {
				return offerCoalescingIf(requireNonNull(frame),
						requireNonNull(coalescingKey), requireNonNull(writeAllowed));
			} finally {
				payloadReleased.run();
			}
		}

		default boolean recheckGuardedFrames() {
			return true;
		}

		default McpOutboundChannel.@NonNull OfferResult offerIfWriteIdleExpired(
				@NonNull Frame frame, long nowNanos, long idleIntervalNanos) {
			return offer(requireNonNull(frame));
		}

		boolean complete(@NonNull Frame terminalFrame);

		default boolean completeWithoutMessage() {
			return completeWithoutMessage(false);
		}

		boolean completeWithoutMessage(boolean discardUncommittedMessages);

		boolean fail(@NonNull StreamTerminationReason reason,
				@Nullable Throwable cause);

		boolean failIfDeadlineExpired(long nowNanos, long deadlineNanos,
				@NonNull StreamTerminationReason reason, @Nullable Throwable cause);

		boolean failIfWriteIdleExpired(long nowNanos, long timeoutNanos,
				@NonNull StreamTerminationReason reason, @Nullable Throwable cause);

		long responseWriteIdleDeadlineNanos(long timeoutNanos);

		void close(@NonNull StreamTerminationReason reason,
				@Nullable Throwable cause);

		@NonNull
		Optional<McpOutboundChannel.@NonNull Snapshot> snapshot();

		boolean isTerminalWritten();
	}

	interface Listener {
		void didTerminate(@NonNull StreamTerminationReason reason,
				@Nullable McpStreamTerminationReason observationReason,
				@Nullable Throwable cause);
	}
	@FunctionalInterface
	interface TestHooks {
		void beforeTerminalReservation();

		default void beforeMessageEnqueue() {
			// No-op outside deterministic race tests.
		}

		default void beforeCoalescingMessageOffer() {
			// No-op outside deterministic race tests.
		}

		default void beforeWriteIdleFailureAttempt(
				@NonNull Runnable competingTermination) {
			requireNonNull(competingTermination);
			// No-op outside deterministic race tests.
		}
	}

	@NonNull
	private static final TestHooks NO_OP_TEST_HOOKS = () -> {
		// No-op outside deterministic race tests.
	};
	@NonNull
	private static volatile TestHooks testHooks = NO_OP_TEST_HOOKS;
	private static final byte @NonNull [] MESSAGE_PREFIX =
			"data: ".getBytes(StandardCharsets.US_ASCII);
	private static final byte @NonNull [] MESSAGE_SUFFIX =
			"\n\n".getBytes(StandardCharsets.US_ASCII);
	private static final byte @NonNull [] KEEP_ALIVE =
			": keepalive\n\n".getBytes(StandardCharsets.US_ASCII);

	@NonNull
	private final McpJsonRpcEnvelopeCodec envelopeCodec;
	@Nullable
	private final McpJsonCodec legacyJsonCodec;
	@NonNull
	private final Channel channel;

	McpRequestSseStream(int frameCapacity, @NonNull McpJsonLimits jsonLimits,
			@NonNull McpJsonRpcEnvelopeCodec envelopeCodec,
			@NonNull McpApplicationClock clock,
			McpOutboundChannel.@NonNull Listener listener) {
		this(frameCapacity, jsonLimits, envelopeCodec, null,
				Mcp20260728ProtocolProfile.INSTANCE, clock, listener);
	}

	McpRequestSseStream(int frameCapacity, @NonNull McpJsonLimits jsonLimits,
			@NonNull McpJsonRpcEnvelopeCodec envelopeCodec,
			@Nullable McpJsonCodec jsonCodec,
			@NonNull McpProtocolProfile protocolProfile,
			@NonNull McpApplicationClock clock,
			McpOutboundChannel.@NonNull Listener listener) {
		requireNonNull(jsonLimits);
		this.envelopeCodec = requireNonNull(envelopeCodec);
		this.legacyJsonCodec = legacyJsonCodec(jsonCodec, protocolProfile);
		int maximumFrameBytes = maximumFrameBytes(jsonLimits);
		this.channel = new TransportChannel(frameCapacity, maximumFrameBytes,
				requireNonNull(clock), requireNonNull(listener));
	}

	static int maximumFrameBytes(@NonNull McpJsonLimits jsonLimits) {
		return Math.addExact(requireNonNull(jsonLimits).maximumOutputBytes(),
				MESSAGE_PREFIX.length + MESSAGE_SUFFIX.length);
	}

	McpRequestSseStream(@NonNull McpJsonRpcEnvelopeCodec envelopeCodec,
			@NonNull Channel channel) {
		this(envelopeCodec, null, Mcp20260728ProtocolProfile.INSTANCE, channel);
	}

	McpRequestSseStream(@NonNull McpJsonRpcEnvelopeCodec envelopeCodec,
			@Nullable McpJsonCodec jsonCodec,
			@NonNull McpProtocolProfile protocolProfile,
			@NonNull Channel channel) {
		this.envelopeCodec = requireNonNull(envelopeCodec);
		this.legacyJsonCodec = legacyJsonCodec(jsonCodec, protocolProfile);
		this.channel = requireNonNull(channel);
	}

	@Nullable
	private static McpJsonCodec legacyJsonCodec(@Nullable McpJsonCodec jsonCodec,
			@NonNull McpProtocolProfile protocolProfile) {
		return McpLegacyHttpWire.isLegacyRevision(requireNonNull(protocolProfile).revision())
				? requireNonNull(jsonCodec, "A legacy SSE stream requires its bounded JSON codec.")
				: null;
	}

	@NonNull
	MicrohttpResponse response(@NonNull List<@NonNull Header> additionalHeaders) {
		requireNonNull(additionalHeaders);
		List<Header> headers = new ArrayList<>(additionalHeaders.size() + 3);
		headers.add(new Header("Content-Type", "text/event-stream"));
		headers.add(new Header("Cache-Control", "no-store"));
		headers.add(new Header("X-Accel-Buffering", "no"));
		headers.addAll(additionalHeaders);
		return channel.response(List.copyOf(headers));
	}

	boolean enqueueMessage(@NonNull McpJsonRpcMessage message)
			throws InterruptedException {
		testHooks.beforeMessageEnqueue();
		return channel.enqueue(frame(requireNonNull(message)));
	}

	McpOutboundChannel.@NonNull OfferResult offerMessage(
			@NonNull McpJsonRpcMessage message) {
		return channel.offer(frame(requireNonNull(message)));
	}

	McpOutboundChannel.@NonNull OfferResult offerCoalescingMessage(
			@NonNull McpJsonRpcMessage message, @NonNull Object coalescingKey) {
		Frame frame = frame(requireNonNull(message));
		testHooks.beforeCoalescingMessageOffer();
		return channel.offerCoalescing(frame,
				requireNonNull(coalescingKey));
	}

	/**
	 * Revalidates a caller-owned boundary immediately before the channel offer.
	 * Encoding and test instrumentation therefore cannot move a catalog frame
	 * past its absolute projection deadline unnoticed.
	 */
	@NonNull
	Optional<McpOutboundChannel.@NonNull OfferResult>
			offerCoalescingMessageIf(@NonNull McpJsonRpcMessage message,
					@NonNull Object coalescingKey,
					@NonNull BooleanSupplier offerAllowed) {
		Frame frame = frame(requireNonNull(message));
		testHooks.beforeCoalescingMessageOffer();
		return channel.offerCoalescingIf(frame, requireNonNull(coalescingKey),
				requireNonNull(offerAllowed));
	}

	@NonNull
	Optional<McpOutboundChannel.@NonNull OfferResult> offerGuardedCoalescingMessage(
			@NonNull McpJsonRpcMessage message, @NonNull Object coalescingKey,
			@NonNull BooleanSupplier writeAllowed, @NonNull Runnable payloadReleased) {
		requireNonNull(payloadReleased);
		boolean handedOff = false;
		try {
			Frame frame = frame(requireNonNull(message));
			requireNonNull(coalescingKey);
			requireNonNull(writeAllowed);
			testHooks.beforeCoalescingMessageOffer();
			handedOff = true;
			return channel.offerGuardedCoalescing(frame, coalescingKey,
					writeAllowed, payloadReleased);
		} finally {
			if (!handedOff)
				payloadReleased.run();
		}
	}

	boolean recheckGuardedFrames() {
		return channel.recheckGuardedFrames();
	}

	boolean completeMessage(@NonNull McpJsonRpcMessage message) {
		Frame terminalFrame = frame(requireNonNull(message));
		testHooks.beforeTerminalReservation();
		return channel.complete(terminalFrame);
	}

	/** Reserves clean SSE completion without a JSON-RPC terminal event. */
	boolean completeWithoutMessage() {
		return completeWithoutMessage(false);
	}

	boolean completeWithoutMessage(boolean discardUncommittedMessages) {
		testHooks.beforeTerminalReservation();
		return channel.completeWithoutMessage(discardUncommittedMessages);
	}

	static void setTestHooks(@Nullable TestHooks testHooks) {
		McpRequestSseStream.testHooks = testHooks == null
				? NO_OP_TEST_HOOKS : testHooks;
	}

	McpOutboundChannel.@NonNull OfferResult offerKeepAlive() {
		return channel.offer(new Frame(FrameType.KEEP_ALIVE_COMMENT, null,
				KEEP_ALIVE));
	}

	McpOutboundChannel.@NonNull OfferResult offerKeepAliveIfWriteIdleExpired(
			long nowNanos, long idleIntervalNanos) {
		return channel.offerIfWriteIdleExpired(
				new Frame(FrameType.KEEP_ALIVE_COMMENT, null, KEEP_ALIVE),
				nowNanos, idleIntervalNanos);
	}

	long responseWriteIdleDeadlineNanos(long timeoutNanos) {
		return channel.responseWriteIdleDeadlineNanos(timeoutNanos);
	}

	boolean fail(@NonNull StreamTerminationReason reason,
			@Nullable Throwable cause) {
		return channel.fail(requireNonNull(reason), cause);
	}

	boolean failIfDeadlineExpired(long nowNanos, long deadlineNanos,
			@NonNull StreamTerminationReason reason,
			@Nullable Throwable cause) {
		return channel.failIfDeadlineExpired(nowNanos, deadlineNanos,
				requireNonNull(reason), cause);
	}

	boolean failIfWriteIdleExpired(long nowNanos, long timeoutNanos,
			@NonNull StreamTerminationReason reason,
			@Nullable Throwable cause) {
		if (timeoutNanos > 0L) {
			long deadlineNanos = channel.responseWriteIdleDeadlineNanos(
					timeoutNanos);
			if (deadlineNanos != Long.MAX_VALUE
					&& nowNanos - deadlineNanos >= 0L)
				testHooks.beforeWriteIdleFailureAttempt(() ->
						channel.fail(requireNonNull(reason), cause));
		}
		return channel.failIfWriteIdleExpired(nowNanos, timeoutNanos,
				requireNonNull(reason), cause);
	}

	void close(@NonNull StreamTerminationReason reason,
			@Nullable Throwable cause) {
		channel.close(requireNonNull(reason), cause);
	}

	Optional<McpOutboundChannel.@NonNull Snapshot> snapshot() {
		return channel.snapshot();
	}

	boolean isTerminalWritten() {
		return channel.isTerminalWritten();
	}

	private @NonNull Frame frame(@NonNull McpJsonRpcMessage message) {
		byte[] json = encodeMessage(envelopeCodec, legacyJsonCodec, message);
		McpJsonObject jsonMessage = legacyJsonCodec != null
				&& message instanceof McpJsonRpcMessage.ResultResponse response
				? McpLegacyResponseWire.projectEnvelope(response) : message.toJsonObject();
		byte[] frame = new byte[MESSAGE_PREFIX.length + json.length
				+ MESSAGE_SUFFIX.length];
		System.arraycopy(MESSAGE_PREFIX, 0, frame, 0, MESSAGE_PREFIX.length);
		System.arraycopy(json, 0, frame, MESSAGE_PREFIX.length, json.length);
		System.arraycopy(MESSAGE_SUFFIX, 0, frame,
				MESSAGE_PREFIX.length + json.length, MESSAGE_SUFFIX.length);
		return new Frame(FrameType.JSON_MESSAGE, message, jsonMessage, frame);
	}

	/** Validates the exact selected-profile JSON bytes before stream side effects. */
	static byte @NonNull [] encodeMessage(@NonNull McpJsonRpcEnvelopeCodec envelopeCodec,
			@Nullable McpJsonCodec jsonCodec,
			@NonNull McpProtocolProfile protocolProfile,
			@NonNull McpJsonRpcMessage message) {
		return encodeMessage(requireNonNull(envelopeCodec),
				legacyJsonCodec(jsonCodec, protocolProfile), message);
	}

	private static byte @NonNull [] encodeMessage(
			@NonNull McpJsonRpcEnvelopeCodec envelopeCodec,
			@Nullable McpJsonCodec legacyJsonCodec,
			@NonNull McpJsonRpcMessage message) {
		McpJsonRpcMessage outbound = McpProtocolSupport.requireServerOutboundMessage(message);
		// The selected application profile already projects result fields. Apply
		// the same final envelope projection as finite legacy responses, omitting
		// modern discriminators and framework-owned result metadata. Progress and
		// errors keep the common JSON-RPC encoding used by their finite paths.
		return legacyJsonCodec != null
				&& outbound instanceof McpJsonRpcMessage.ResultResponse response
				? McpLegacyResponseWire.encode(legacyJsonCodec, response)
				: envelopeCodec.encode(outbound);
	}

	@ThreadSafe
	private static final class TransportChannel implements Channel {
		@NonNull
		private final McpOutboundChannel delegate;

		private TransportChannel(int frameCapacity, int maximumFrameBytes,
				@NonNull McpApplicationClock clock,
				McpOutboundChannel.@NonNull Listener listener) {
			this.delegate = new McpOutboundChannel(frameCapacity,
					maximumFrameBytes, maximumFrameBytes,
					requireNonNull(clock)::nanoTime, requireNonNull(listener));
		}

		@Override
		@NonNull
		public MicrohttpResponse response(@NonNull List<@NonNull Header> headers) {
			return StreamingMicrohttpResponses.withWritableSourceBody(
					200, "OK", List.copyOf(requireNonNull(headers)),
					this.delegate::newWritableSource);
		}

		@Override
		public boolean enqueue(@NonNull Frame frame) throws InterruptedException {
			return this.delegate.enqueue(requireNonNull(frame).encodedBytes());
		}

		@Override
		public McpOutboundChannel.@NonNull OfferResult offer(
				@NonNull Frame frame) {
			return this.delegate.offer(requireNonNull(frame).encodedBytes());
		}

		@Override
		public McpOutboundChannel.@NonNull OfferResult offerCoalescing(
				@NonNull Frame frame, @NonNull Object coalescingKey) {
			return this.delegate.offerCoalescing(
					requireNonNull(frame).encodedBytes(), requireNonNull(coalescingKey));
		}

		@Override
		@NonNull
		public Optional<McpOutboundChannel.@NonNull OfferResult> offerCoalescingIf(
				@NonNull Frame frame, @NonNull Object coalescingKey,
				@NonNull BooleanSupplier offerAllowed) {
			return this.delegate.offerCoalescingIf(
					requireNonNull(frame).encodedBytes(), requireNonNull(coalescingKey),
					requireNonNull(offerAllowed));
		}

		@Override
		@NonNull
		public Optional<McpOutboundChannel.@NonNull OfferResult> offerGuardedCoalescing(
				@NonNull Frame frame, @NonNull Object coalescingKey,
				@NonNull BooleanSupplier writeAllowed, @NonNull Runnable payloadReleased) {
			requireNonNull(payloadReleased);
			boolean handedOff = false;
			try {
				byte[] encodedBytes = requireNonNull(frame).encodedBytes();
				requireNonNull(coalescingKey);
				requireNonNull(writeAllowed);
				handedOff = true;
				return this.delegate.offerGuardedCoalescing(encodedBytes,
						coalescingKey, writeAllowed, payloadReleased);
			} finally {
				if (!handedOff)
					payloadReleased.run();
			}
		}

		@Override
		public boolean recheckGuardedFrames() {
			return this.delegate.recheckGuardedFrames();
		}

		@Override
		public McpOutboundChannel.@NonNull OfferResult offerIfWriteIdleExpired(
				@NonNull Frame frame, long nowNanos, long idleIntervalNanos) {
			return this.delegate.offerIfWriteIdleExpired(
					requireNonNull(frame).encodedBytes(), nowNanos,
					idleIntervalNanos);
		}

		@Override
		public boolean complete(@NonNull Frame terminalFrame) {
			return this.delegate.complete(
					requireNonNull(terminalFrame).encodedBytes());
		}

		@Override
		public boolean completeWithoutMessage(boolean discardUncommittedMessages) {
			return this.delegate.completeWithoutPayload(discardUncommittedMessages);
		}

		@Override
		public boolean fail(@NonNull StreamTerminationReason reason,
				@Nullable Throwable cause) {
			return this.delegate.fail(requireNonNull(reason), cause);
		}

		@Override
		public boolean failIfDeadlineExpired(long nowNanos, long deadlineNanos,
				@NonNull StreamTerminationReason reason, @Nullable Throwable cause) {
			return this.delegate.failIfDeadlineExpired(nowNanos, deadlineNanos,
					requireNonNull(reason), cause);
		}

		@Override
		public boolean failIfWriteIdleExpired(long nowNanos, long timeoutNanos,
				@NonNull StreamTerminationReason reason, @Nullable Throwable cause) {
			return this.delegate.failIfWriteIdleExpired(nowNanos, timeoutNanos,
					requireNonNull(reason), cause);
		}

		@Override
		public long responseWriteIdleDeadlineNanos(long timeoutNanos) {
			return this.delegate.responseWriteIdleDeadlineNanos(timeoutNanos);
		}

		@Override
		public void close(@NonNull StreamTerminationReason reason,
				@Nullable Throwable cause) {
			this.delegate.close(requireNonNull(reason), cause);
		}

		@Override
		@NonNull
		public Optional<McpOutboundChannel.@NonNull Snapshot> snapshot() {
			return Optional.of(this.delegate.snapshot());
		}

		@Override
		public boolean isTerminalWritten() {
			return this.delegate.isTerminalWritten();
		}
	}
}
