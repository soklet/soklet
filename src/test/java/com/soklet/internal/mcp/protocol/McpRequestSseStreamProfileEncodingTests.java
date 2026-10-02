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
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpResponse;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Exact finite/SSE projections, modern parity and bounded legacy encoding. */
public class McpRequestSseStreamProfileEncodingTests {
	private static final McpJsonCodec JSON = new McpJsonCodec(McpJsonLimits.productionDefaults());
	private static final McpJsonRpcEnvelopeCodec ENVELOPES = new McpJsonRpcEnvelopeCodec(JSON);
	private static final List<Mcp2025ProtocolProfile> LEGACY = List.of(
			Mcp2025ProtocolProfile.JUNE_18, Mcp2025ProtocolProfile.NOVEMBER_25);
	private static final McpJsonRpcId ID = new McpJsonRpcId.StringId("call");
	private static final McpResultMetadata METADATA = new McpResultMetadata(
			Optional.of(McpImplementationMetadata.withNameAndVersion("server", "4.0.0")),
			object("{\"example.test/receipt\":\"opaque\"}"));
	private static final List<ResultCase> RESULTS = List.of(
			new ResultCase(McpProfileApplicationResultKind.TOOL,
					"{\"content\":[{\"type\":\"text\",\"text\":\"Hello\\n🙂\"}],\"isError\":false}",
					"{\"content\":[{\"type\":\"text\",\"text\":\"Hello\\n🙂\"}],\"isError\":false}"),
			new ResultCase(McpProfileApplicationResultKind.PROMPT,
					"{\"description\":\"Result\",\"messages\":[{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"Hello\"}}]}",
					"{\"description\":\"Result\",\"messages\":[{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"Hello\"}}]}"),
			new ResultCase(McpProfileApplicationResultKind.RESOURCE_READ,
					"{\"contents\":[{\"uri\":\"catalog://items/1\",\"text\":\"payload\"}],\"cacheScope\":\"private\",\"ttlMs\":5}",
					"{\"contents\":[{\"uri\":\"catalog://items/1\",\"text\":\"payload\"}]}"),
			new ResultCase(McpProfileApplicationResultKind.RESOURCE_LIST,
					"{\"resources\":[{\"uri\":\"catalog://items/1\",\"name\":\"Item\"}]}",
					"{\"resources\":[{\"uri\":\"catalog://items/1\",\"name\":\"Item\"}]}"),
			new ResultCase(McpProfileApplicationResultKind.COMPLETION,
					"{\"completion\":{\"values\":[\"α\",\"🙂\"],\"total\":3,\"hasMore\":true}}",
					"{\"completion\":{\"values\":[\"α\",\"🙂\"],\"total\":3,\"hasMore\":true}}"));

	@Test
	public void legacyTerminalFramesMatchFiniteProjectionForEveryApplicationKind() {
		for (Mcp2025ProtocolProfile profile : LEGACY)
			for (ResultCase resultCase : RESULTS) {
				McpWireResult canonical = McpWireResult.complete(object(resultCase.canonical()),
						Optional.of(METADATA));
				McpWireResult projected = profile.renderApplicationResult(resultCase.kind(), canonical);
				McpJsonRpcMessage.ResultResponse response = new McpJsonRpcMessage.ResultResponse(
						ID, projected, McpJsonObject.empty());
				RecordingChannel channel = new RecordingChannel();
				McpRequestSseStream stream = new McpRequestSseStream(ENVELOPES, JSON, profile, channel);
				assertTrue(stream.completeMessage(response));
				assertEquals(1, channel.frames.size());
				byte[] finite = McpLegacyResponseWire.encode(JSON, response);
				assertFrame(channel.frames.get(0), finite);
				assertArrayEquals(finite, McpRequestSseStream.encodeMessage(
						ENVELOPES, JSON, profile, response));
				Map<String, McpJsonValue> expectedResult = new java.util.LinkedHashMap<>(
						object(resultCase.legacy()).members());
				expectedResult.put("_meta", METADATA.extensionFields());
				assertEquals(new McpJsonObject(Map.of("jsonrpc", new McpJsonString("2.0"),
						"id", ID.toJsonValue(), "result", new McpJsonObject(expectedResult))),
						JSON.parse(finite), profile.revision() + ":" + resultCase.kind());
				String frame = text(channel.frames.get(0));
				assertFalse(frame.contains("resultType"), frame);
				assertFalse(frame.contains(McpResultMetadata.SERVER_INFORMATION_KEY), frame);
				assertFalse(frame.contains("cacheScope"), frame);
				assertFalse(frame.contains("ttlMs"), frame);
			}
	}

	@Test
	public void legacyProgressAndErrorsKeepTheirCommonJsonRpcEnvelopesWithoutPriming() throws Exception {
		List<McpJsonRpcMessage.Notification> notifications = List.of(
				new McpJsonRpcMessage.Notification("notifications/progress",
						Optional.of(object("{\"progressToken\":\"token\\n🙂\",\"progress\":1,\"total\":2,\"message\":\"Working\"}")),
						McpJsonObject.empty()),
				new McpJsonRpcMessage.Notification("notifications/progress",
						Optional.of(object("{\"progressToken\":7,\"progress\":0.5}")), McpJsonObject.empty()));
		McpJsonRpcMessage.ErrorResponse error = new McpJsonRpcMessage.ErrorResponse(Optional.of(ID),
				new McpJsonRpcError(-32603, "Internal error", Optional.empty()), McpJsonObject.empty());
		for (Mcp2025ProtocolProfile profile : LEGACY) {
			RecordingChannel channel = new RecordingChannel();
			McpRequestSseStream stream = new McpRequestSseStream(ENVELOPES, JSON, profile, channel);
			for (McpJsonRpcMessage.Notification notification : notifications)
				assertTrue(stream.enqueueMessage(notification));
			assertTrue(stream.completeMessage(error));
			assertEquals(3, channel.frames.size(), "No empty priming message or history marker.");
			for (int index = 0; index < notifications.size(); index++)
				assertFrame(channel.frames.get(index), ENVELOPES.encode(notifications.get(index)));
			assertFrame(channel.frames.get(2), ENVELOPES.encode(error));
			assertFalse(text(channel.frames.get(2)).contains("resultType"));
		}
	}

	@Test
	public void modernDefaultAndExplicitModernProfileKeepExactCanonicalBytes() {
		McpJsonRpcMessage.ResultResponse complete = new McpJsonRpcMessage.ResultResponse(ID,
				McpWireResult.complete(object(RESULTS.get(0).canonical()), Optional.of(METADATA)),
				McpJsonObject.empty());
		McpJsonRpcMessage.ResultResponse inputRequired = new McpJsonRpcMessage.ResultResponse(ID,
				McpWireResult.inputRequired("tools/call", Optional.empty(), Optional.of("opaque"),
						Optional.empty(), McpJsonObject.empty()), McpJsonObject.empty());
		for (McpJsonRpcMessage.ResultResponse response : List.of(complete, inputRequired)) {
			RecordingChannel defaultChannel = new RecordingChannel();
			RecordingChannel explicitChannel = new RecordingChannel();
			assertTrue(new McpRequestSseStream(ENVELOPES, defaultChannel).completeMessage(response));
			assertTrue(new McpRequestSseStream(ENVELOPES, JSON,
					Mcp20260728ProtocolProfile.INSTANCE, explicitChannel).completeMessage(response));
			assertFrame(defaultChannel.frames.get(0), ENVELOPES.encode(response));
			assertArrayEquals(defaultChannel.frames.get(0).encodedBytes(),
					explicitChannel.frames.get(0).encodedBytes());
			assertArrayEquals(ENVELOPES.encode(response), McpRequestSseStream.encodeMessage(
					ENVELOPES, JSON, Mcp20260728ProtocolProfile.INSTANCE, response));
		}
		assertTrue(new String(ENVELOPES.encode(complete), StandardCharsets.UTF_8).contains("resultType"));
		assertTrue(new String(ENVELOPES.encode(complete), StandardCharsets.UTF_8)
				.contains(McpResultMetadata.SERVER_INFORMATION_KEY));
	}

	@Test
	public void legacyByteLimitAppliesAfterProjectionAndBeforeChannelMutation() {
		McpJsonRpcMessage.ResultResponse response = new McpJsonRpcMessage.ResultResponse(ID,
				McpWireResult.complete(object("{\"content\":[]}"), Optional.of(METADATA)), McpJsonObject.empty());
		int legacyBytes = McpLegacyResponseWire.encode(JSON, response).length;
		for (Mcp2025ProtocolProfile profile : LEGACY) {
			McpJsonLimits exactLimits = limitsWithOutputBytes(legacyBytes);
			McpJsonCodec exactJson = new McpJsonCodec(exactLimits);
			McpJsonRpcEnvelopeCodec exactEnvelopes = new McpJsonRpcEnvelopeCodec(exactJson);
			assertThrows(IllegalArgumentException.class, () -> exactEnvelopes.encode(response),
					"The canonical modern envelope is larger than the legacy projection.");
			assertEquals(legacyBytes, McpRequestSseStream.encodeMessage(
					exactEnvelopes, exactJson, profile, response).length);
			McpRequestSseStream exact = new McpRequestSseStream(1, exactLimits, exactEnvelopes,
					exactJson, profile, () -> 0L, NO_OP_LISTENER);
			assertTrue(exact.completeMessage(response));
			assertEquals(legacyBytes + 8, exact.snapshot().orElseThrow().terminalBytes());
			McpJsonLimits smallerLimits = limitsWithOutputBytes(legacyBytes - 1);
			McpJsonCodec smallerJson = new McpJsonCodec(smallerLimits);
			McpJsonRpcEnvelopeCodec smallerEnvelopes = new McpJsonRpcEnvelopeCodec(smallerJson);
			McpRequestSseStream smaller = new McpRequestSseStream(1, smallerLimits,
					smallerEnvelopes, smallerJson, profile, () -> 0L, NO_OP_LISTENER);
			assertThrows(IllegalArgumentException.class, () -> smaller.completeMessage(response));
			assertFalse(smaller.snapshot().orElseThrow().terminalReserved());
			assertEquals(0, smaller.snapshot().orElseThrow().terminalBytes());
		}
	}

	@Test
	public void legacyRejectsServerRequestsAndNonCompleteResultsBeforeChannelMutation() {
		McpJsonRpcMessage.Request request = new McpJsonRpcMessage.Request(
				new McpJsonRpcId.StringId("server-request"), "elicitation/create",
				new McpRequestParameters(McpRequestMetadata.fromClientCapabilities(
						Mcp20260728ProtocolProfile.INSTANCE, McpClientCapabilities.empty()), McpJsonObject.empty()),
				McpJsonObject.empty());
		McpJsonRpcMessage.ResultResponse inputRequired = new McpJsonRpcMessage.ResultResponse(ID,
				McpWireResult.inputRequired("tools/call", Optional.empty(), Optional.of("opaque"),
						Optional.empty(), McpJsonObject.empty()), McpJsonObject.empty());
		for (Mcp2025ProtocolProfile profile : LEGACY) {
			RecordingChannel channel = new RecordingChannel();
			McpRequestSseStream stream = new McpRequestSseStream(ENVELOPES, JSON, profile, channel);
			assertThrows(IllegalArgumentException.class, () -> stream.completeMessage(request));
			assertThrows(IllegalArgumentException.class, () -> stream.completeMessage(inputRequired));
			assertThrows(IllegalArgumentException.class, () -> McpRequestSseStream.encodeMessage(
						ENVELOPES, JSON, profile, request));
			assertTrue(channel.frames.isEmpty());
		}
	}

	private static void assertFrame(McpRequestSseStream.Frame frame, byte[] json) {
		assertEquals(McpRequestSseStream.FrameType.JSON_MESSAGE, frame.type());
		assertEquals(JSON.parse(json), frame.jsonMessage(),
				"The simulator projection must match the selected-profile encoded bytes.");
		assertEquals("data: " + new String(json, StandardCharsets.UTF_8) + "\n\n", text(frame));
		assertTrue(text(frame).lines().noneMatch(line -> line.startsWith("id:")
				|| line.startsWith("event:") || line.startsWith("retry:")));
	}

	private static String text(McpRequestSseStream.Frame frame) {
		return new String(frame.encodedBytes(), StandardCharsets.UTF_8);
	}

	private static McpJsonObject object(String json) {
		return (McpJsonObject) JSON.parse(json);
	}

	private static McpJsonLimits limitsWithOutputBytes(int maximumOutputBytes) {
		McpJsonLimits defaults = McpJsonLimits.productionDefaults();
		return new McpJsonLimits(defaults.maximumInputBytes(), defaults.maximumNestingDepth(),
				defaults.maximumTokenLengthInCharacters(), defaults.maximumStringLengthInCharacters(),
				defaults.maximumNumberLengthInCharacters(), defaults.maximumExponentMagnitude(),
				defaults.maximumNodeCount(), maximumOutputBytes);
	}

	private static final McpOutboundChannel.Listener NO_OP_LISTENER = new McpOutboundChannel.Listener() {
		@Override public void didWrite(long byteCount, long timestampNanos) { }
		@Override public void didApplyBackpressure() { }
		@Override public void didTerminate(@NonNull StreamTerminationReason reason, @Nullable Throwable cause) { }
	};

	private record ResultCase(McpProfileApplicationResultKind kind, String canonical, String legacy) { }

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
