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
			@Nullable McpJsonRpcMessage message, byte @NonNull [] encodedBytes) {
		Frame {
			requireNonNull(type);
			encodedBytes = Arrays.copyOf(requireNonNull(encodedBytes),
					encodedBytes.length);
			if ((type == FrameType.JSON_MESSAGE) != (message != null))
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

		void enqueue(@NonNull Frame frame) throws InterruptedException;

		McpOutboundChannel.@NonNull OfferResult offer(@NonNull Frame frame);

		McpOutboundChannel.@NonNull OfferResult offerCoalescing(
				@NonNull Frame frame, @NonNull Object coalescingKey);

		boolean complete(@NonNull Frame terminalFrame);

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
	@NonNull
	private final Channel channel;

	McpRequestSseStream(int frameCapacity, @NonNull McpJsonLimits jsonLimits,
			@NonNull McpJsonRpcEnvelopeCodec envelopeCodec,
			@NonNull McpApplicationClock clock,
			McpOutboundChannel.@NonNull Listener listener) {
		requireNonNull(jsonLimits);
		this.envelopeCodec = requireNonNull(envelopeCodec);
		int maximumFrameBytes = Math.addExact(jsonLimits.maximumOutputBytes(),
				MESSAGE_PREFIX.length + MESSAGE_SUFFIX.length);
		this.channel = new TransportChannel(frameCapacity, maximumFrameBytes,
				requireNonNull(clock), requireNonNull(listener));
	}

	McpRequestSseStream(@NonNull McpJsonRpcEnvelopeCodec envelopeCodec,
			@NonNull Channel channel) {
		this.envelopeCodec = requireNonNull(envelopeCodec);
		this.channel = requireNonNull(channel);
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

	void enqueueMessage(@NonNull McpJsonRpcMessage message) throws InterruptedException {
		channel.enqueue(frame(requireNonNull(message)));
	}

	McpOutboundChannel.@NonNull OfferResult offerMessage(
			@NonNull McpJsonRpcMessage message) {
		return channel.offer(frame(requireNonNull(message)));
	}

	McpOutboundChannel.@NonNull OfferResult offerCoalescingMessage(
			@NonNull McpJsonRpcMessage message, @NonNull Object coalescingKey) {
		return channel.offerCoalescing(frame(requireNonNull(message)),
				requireNonNull(coalescingKey));
	}

	boolean completeMessage(@NonNull McpJsonRpcMessage message) {
		Frame terminalFrame = frame(requireNonNull(message));
		testHooks.beforeTerminalReservation();
		return channel.complete(terminalFrame);
	}

	static void setTestHooks(@Nullable TestHooks testHooks) {
		McpRequestSseStream.testHooks = testHooks == null
				? NO_OP_TEST_HOOKS : testHooks;
	}

	McpOutboundChannel.@NonNull OfferResult offerKeepAlive() {
		return channel.offer(new Frame(FrameType.KEEP_ALIVE_COMMENT, null,
				KEEP_ALIVE));
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
		byte[] json = envelopeCodec.encode(
				McpProtocolSupport.requireServerOutboundMessage(message));
		byte[] frame = new byte[MESSAGE_PREFIX.length + json.length
				+ MESSAGE_SUFFIX.length];
		System.arraycopy(MESSAGE_PREFIX, 0, frame, 0, MESSAGE_PREFIX.length);
		System.arraycopy(json, 0, frame, MESSAGE_PREFIX.length, json.length);
		System.arraycopy(MESSAGE_SUFFIX, 0, frame,
				MESSAGE_PREFIX.length + json.length, MESSAGE_SUFFIX.length);
		return new Frame(FrameType.JSON_MESSAGE, message, frame);
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
		public void enqueue(@NonNull Frame frame) throws InterruptedException {
			this.delegate.enqueue(requireNonNull(frame).encodedBytes());
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
		public boolean complete(@NonNull Frame terminalFrame) {
			return this.delegate.complete(
					requireNonNull(terminalFrame).encodedBytes());
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
