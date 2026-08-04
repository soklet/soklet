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
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpResponse;
import com.soklet.internal.microhttp.StreamingMicrohttpResponses;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * One lazily committed, request-scoped MCP SSE response. JSON-RPC messages
 * use the default SSE message event through a {@code data} field; HTTP chunk
 * framing remains the responsibility of {@link McpOutboundChannel}.
 */
final class McpRequestSseStream {
	@FunctionalInterface
	interface TestHooks {
		void beforeTerminalReservation();
	}

	private static final TestHooks NO_OP_TEST_HOOKS = () -> {
		// No-op outside deterministic race tests.
	};
	private static volatile TestHooks testHooks = NO_OP_TEST_HOOKS;
	private static final byte[] MESSAGE_PREFIX =
			"data: ".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] MESSAGE_SUFFIX =
			"\n\n".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] KEEP_ALIVE =
			": keepalive\n\n".getBytes(StandardCharsets.US_ASCII);

	private final McpJsonRpcEnvelopeCodec envelopeCodec;
	private final McpOutboundChannel channel;

	McpRequestSseStream(int frameCapacity, McpJsonLimits jsonLimits,
			McpJsonRpcEnvelopeCodec envelopeCodec, McpApplicationClock clock,
			McpOutboundChannel.Listener listener) {
		requireNonNull(jsonLimits);
		this.envelopeCodec = requireNonNull(envelopeCodec);
		int maximumFrameBytes = Math.addExact(jsonLimits.maximumOutputBytes(),
				MESSAGE_PREFIX.length + MESSAGE_SUFFIX.length);
		this.channel = new McpOutboundChannel(frameCapacity, maximumFrameBytes,
				maximumFrameBytes, requireNonNull(clock)::nanoTime,
				requireNonNull(listener));
	}

	MicrohttpResponse response(List<Header> additionalHeaders) {
		requireNonNull(additionalHeaders);
		List<Header> headers = new ArrayList<>(additionalHeaders.size() + 3);
		headers.add(new Header("Content-Type", "text/event-stream"));
		headers.add(new Header("Cache-Control", "no-store"));
		headers.add(new Header("X-Accel-Buffering", "no"));
		headers.addAll(additionalHeaders);
		return StreamingMicrohttpResponses.withWritableSourceBody(
				200, "OK", List.copyOf(headers), channel::newWritableSource);
	}

	void enqueueMessage(McpJsonRpcMessage message) throws InterruptedException {
		channel.enqueue(frame(requireNonNull(message)));
	}

	McpOutboundChannel.OfferResult offerMessage(McpJsonRpcMessage message) {
		return channel.offer(frame(requireNonNull(message)));
	}

	boolean completeMessage(McpJsonRpcMessage message) {
		byte[] terminalFrame = frame(requireNonNull(message));
		testHooks.beforeTerminalReservation();
		return channel.complete(terminalFrame);
	}

	static void setTestHooks(@Nullable TestHooks testHooks) {
		McpRequestSseStream.testHooks = testHooks == null
				? NO_OP_TEST_HOOKS : testHooks;
	}

	McpOutboundChannel.OfferResult offerKeepAlive() {
		return channel.offer(KEEP_ALIVE);
	}

	boolean fail(StreamTerminationReason reason, @Nullable Throwable cause) {
		return channel.fail(requireNonNull(reason), cause);
	}

	boolean failIfDeadlineExpired(long nowNanos, long deadlineNanos,
			StreamTerminationReason reason, @Nullable Throwable cause) {
		return channel.failIfDeadlineExpired(nowNanos, deadlineNanos,
				requireNonNull(reason), cause);
	}

	boolean failIfWriteIdleExpired(long nowNanos, long timeoutNanos,
			StreamTerminationReason reason, @Nullable Throwable cause) {
		return channel.failIfWriteIdleExpired(nowNanos, timeoutNanos,
				requireNonNull(reason), cause);
	}

	void close(StreamTerminationReason reason, @Nullable Throwable cause) {
		channel.close(requireNonNull(reason), cause);
	}

	McpOutboundChannel.Snapshot snapshot() {
		return channel.snapshot();
	}

	private byte[] frame(McpJsonRpcMessage message) {
		byte[] json = envelopeCodec.encode(message);
		byte[] frame = new byte[MESSAGE_PREFIX.length + json.length
				+ MESSAGE_SUFFIX.length];
		System.arraycopy(MESSAGE_PREFIX, 0, frame, 0, MESSAGE_PREFIX.length);
		System.arraycopy(json, 0, frame, MESSAGE_PREFIX.length, json.length);
		System.arraycopy(MESSAGE_SUFFIX, 0, frame,
				MESSAGE_PREFIX.length + json.length, MESSAGE_SUFFIX.length);
		return frame;
	}
}
