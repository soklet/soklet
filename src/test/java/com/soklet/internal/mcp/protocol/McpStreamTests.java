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

import com.soklet.CorsAuthorizer;
import com.soklet.McpAdmissionController;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpInputRequest;
import com.soklet.McpInputRequestDeclaration;
import com.soklet.McpInputRequiredResult;
import com.soklet.McpInputRequirement;
import com.soklet.McpProgressReporter;
import com.soklet.McpProgressUpdate;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpRequestOutcome;
import com.soklet.McpResourceOutput;
import com.soklet.McpResourceRegistration;
import com.soklet.McpServer;
import com.soklet.McpSubscriptionConfig;
import com.soklet.McpSubscriptionEvent;
import com.soklet.McpSubscriptionEventListener;
import com.soklet.McpSubscriptionEventPublisher;
import com.soklet.McpSubscriptionEventRegistration;
import com.soklet.McpSubscriptionNotificationType;
import com.soklet.McpTextResourceContents;
import com.soklet.McpToolRegistration;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.StreamTerminationReason;
import com.soklet.internal.mcp.transport.McpOutboundChannel;
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpResponse;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.concurrent.ThreadSafe;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Negative coverage for the MCP server-to-client JSON-RPC direction boundary.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpStreamTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String TOOL_NAME = "stream.input";
	private static final URI RESOURCE_URI = URI.create("test://stream/resource");
	private static final String SERVER_REQUEST_REJECTION =
			"MCP servers must not write independent JSON-RPC requests.";
	private static final McpJsonRpcEnvelopeCodec ENVELOPE_CODEC =
			new McpJsonRpcEnvelopeCodec(
					new McpJsonCodec(McpJsonLimits.productionDefaults()));

	@Test
	public void neverWritesIndependentRequest() throws Exception {
		RecordingPublisher publisher = new RecordingPublisher();
		McpInputRequestDeclaration roots = McpInputRequestDeclaration
				.fromRoots(McpInputRequirement.REQUIRED);
		McpToolRegistration<com.soklet.McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonArguments()
				.handler((request, arguments, features) -> {
					features.find(McpProgressReporter.class).ifPresent(reporter ->
							reporter.report(McpProgressUpdate.withProgress(1.0).build()));
					return McpInputRequiredResult.builder()
							.inputRequest("roots", McpInputRequest.fromDeclaration(
									roots, com.soklet.McpJsonObject.emptyInstance()))
							.build();
				})
				.mayRequestInput(roots)
				.build();
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisher(publisher)
				.notificationType(
						McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED)
				.build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(RESOURCE_URI, "Stream test resource")
				.handler((request, read, features) ->
						McpCompleteResult.fromResourceOutput(
								McpResourceOutput.builder()
										.content(McpTextResourceContents
												.withUriAndText(read.getUri(), "test")
												.build())
										.build()))
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"stream-test", "4.0.0-SNAPSHOT").build())
				.tool(tool)
				.resource(resource)
				.subscriptions(subscriptions)
				.build();
		McpServer server = McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
		Soklet soklet = managedSoklet(server);
		Thread stopThread = null;

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow()
					.getPort();

			try (McpChunkedHttpClient client = callTool(port, "\"json\"", null)) {
				McpChunkedHttpClient.HttpResponseHead head = client.readHead();
				assertJsonHead(head);
				McpJsonRpcEnvelope envelope = ENVELOPE_CODEC.decode(
						client.readFixedBody(head));
				assertNoIndependentRequests(List.of(envelope));
				assertEmbeddedInputRequest(Assertions.assertInstanceOf(
						McpJsonRpcEnvelope.ResultResponse.class, envelope),
						new McpJsonRpcId.StringId("json"));
			}

			try (McpChunkedHttpClient client = callTool(port, "17", "\"progress\"")) {
				assertSseHead(client.readHead());
				List<McpJsonRpcEnvelope> envelopes = readAllSseEnvelopes(client);
				assertNoIndependentRequests(envelopes);
				Assertions.assertEquals(2, envelopes.size(), envelopes.toString());
				McpJsonRpcEnvelope.Notification progress = Assertions.assertInstanceOf(
						McpJsonRpcEnvelope.Notification.class, envelopes.get(0));
				Assertions.assertEquals("notifications/progress", progress.method());
				assertEmbeddedInputRequest(Assertions.assertInstanceOf(
						McpJsonRpcEnvelope.ResultResponse.class, envelopes.get(1)),
						new McpJsonRpcId.IntegerId(BigInteger.valueOf(17)));
			}

			try (McpChunkedHttpClient client = listen(port, "\"subscription\"")) {
				assertSseHead(client.readHead());
				List<McpJsonRpcEnvelope> envelopes = new ArrayList<>();
				envelopes.add(readNextSseEnvelope(client));
				publisher.publishResourcesListChanged();
				envelopes.add(readNextSseEnvelope(client));

				stopThread = new Thread(soklet::close,
						"mcp-no-independent-request-stop");
				stopThread.start();
				envelopes.addAll(readAllSseEnvelopes(client));
				stopThread.join(5_000L);
				Assertions.assertFalse(stopThread.isAlive(),
						"The MCP server did not finish graceful subscription shutdown.");

				assertNoIndependentRequests(envelopes);
				Assertions.assertEquals(3, envelopes.size(), envelopes.toString());
				Assertions.assertEquals(
						"notifications/subscriptions/acknowledged",
						Assertions.assertInstanceOf(
								McpJsonRpcEnvelope.Notification.class,
								envelopes.get(0)).method());
				Assertions.assertEquals("notifications/resources/list_changed",
						Assertions.assertInstanceOf(
								McpJsonRpcEnvelope.Notification.class,
								envelopes.get(1)).method());
				McpJsonRpcEnvelope.ResultResponse terminal =
						Assertions.assertInstanceOf(
								McpJsonRpcEnvelope.ResultResponse.class,
								envelopes.get(2));
				Assertions.assertEquals(new McpJsonRpcId.StringId("subscription"),
						terminal.id());
			}
		} finally {
			soklet.close();
			if (stopThread != null && stopThread.isAlive())
				stopThread.join(5_000L);
		}
	}

	@Test
	public void outboundBoundariesRejectIndependentRequestsBeforeEncodingOrChannelMutation()
			throws Exception {
		McpJsonRpcMessage.Request request = new McpJsonRpcMessage.Request(
				new McpJsonRpcId.StringId("server-request"), "roots/list",
				new McpRequestParameters(McpRequestMetadata.fromClientCapabilities(
						Mcp20260728ProtocolProfile.INSTANCE,
						McpClientCapabilities.empty()), McpJsonObject.empty()),
				McpJsonObject.empty());

		IllegalArgumentException responseFailure = Assertions.assertThrows(
				IllegalArgumentException.class, () -> new McpApplicationResponse(
						200, "OK", Optional.of(request), McpRequestOutcome.COMPLETE,
						List.of()));
		Assertions.assertEquals(SERVER_REQUEST_REJECTION,
				responseFailure.getMessage());

		McpJsonLimits oneByteOutput = new McpJsonLimits(
				1_024, 16, 1_024, 1_024, 128, 100, 1_024, 1);
		RecordingChannel channel = new RecordingChannel();
		McpRequestSseStream stream = new McpRequestSseStream(
				new McpJsonRpcEnvelopeCodec(new McpJsonCodec(oneByteOutput)), channel);
		List<Executable> attempts = List.of(
				() -> stream.enqueueMessage(request),
				() -> stream.offerMessage(request),
				() -> stream.offerCoalescingMessage(request, "request"),
				() -> stream.completeMessage(request));

		for (Executable attempt : attempts) {
			IllegalArgumentException failure = Assertions.assertThrows(
					IllegalArgumentException.class, attempt);
			Assertions.assertEquals(SERVER_REQUEST_REJECTION,
					failure.getMessage(),
					"The direction guard must win before the one-byte encoder limit.");
			Assertions.assertEquals(0, channel.mutations(),
					"A rejected request must not reach the outbound channel.");
		}
	}

	@Test
	public void sseFrameCapacityIncludesExactJsonEnvelopeAndFramingBytes() {
		McpJsonRpcMessage.Notification notification =
				new McpJsonRpcMessage.Notification("notifications/test",
						Optional.empty(), McpJsonObject.empty());
		int jsonBytes = ENVELOPE_CODEC.encode(notification).length;
		McpJsonLimits exactLimits = limitsWithOutputBytes(jsonBytes);
		McpRequestSseStream exact = new McpRequestSseStream(1, exactLimits,
				new McpJsonRpcEnvelopeCodec(new McpJsonCodec(exactLimits)),
				() -> 0L, NO_OP_OUTBOUND_LISTENER);

		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED,
				exact.offerMessage(notification));
		McpOutboundChannel.Snapshot snapshot = exact.snapshot().orElseThrow();
		Assertions.assertEquals(jsonBytes + 8, snapshot.byteCapacity());
		Assertions.assertEquals(jsonBytes + 8,
				snapshot.terminalByteCapacity());
		Assertions.assertEquals(jsonBytes + 8, snapshot.bufferedBytes());

		McpJsonLimits oneUnderLimits = limitsWithOutputBytes(jsonBytes - 1);
		McpRequestSseStream oneUnder = new McpRequestSseStream(1,
				oneUnderLimits, new McpJsonRpcEnvelopeCodec(
						new McpJsonCodec(oneUnderLimits)), () -> 0L,
				NO_OP_OUTBOUND_LISTENER);
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> oneUnder.offerMessage(notification));
		Assertions.assertEquals(0,
				oneUnder.snapshot().orElseThrow().bufferedBytes());
	}

	private static final McpOutboundChannel.Listener NO_OP_OUTBOUND_LISTENER =
			new McpOutboundChannel.Listener() {
				@Override
				public void didWrite(long byteCount, long timestampNanos) {
				}

				@Override
				public void didApplyBackpressure() {
				}

				@Override
				public void didTerminate(@NonNull StreamTerminationReason reason,
						@Nullable Throwable cause) {
				}
			};

	private static McpJsonLimits limitsWithOutputBytes(int maximumOutputBytes) {
		McpJsonLimits defaults = McpJsonLimits.productionDefaults();
		return new McpJsonLimits(defaults.maximumInputBytes(),
				defaults.maximumNestingDepth(),
				defaults.maximumTokenLengthInCharacters(),
				defaults.maximumStringLengthInCharacters(),
				defaults.maximumNumberLengthInCharacters(),
				defaults.maximumExponentMagnitude(), defaults.maximumNodeCount(),
				maximumOutputBytes);
	}

	private static Soklet managedSoklet(McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.build());
	}

	private static McpChunkedHttpClient callTool(int port, String idJson,
			@Nullable String progressTokenJson) throws Exception {
		String progressToken = progressTokenJson == null ? ""
				: ",\"progressToken\":" + progressTokenJson;
		String body = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
				+ ",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{\"roots\":{}}"
				+ progressToken + "},\"name\":\"" + TOOL_NAME
				+ "\",\"arguments\":{}}}";
		return McpChunkedHttpClient.postMcpMessage(port, body, List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", PROTOCOL_VERSION),
				new McpChunkedHttpClient.RequestHeader("Mcp-Method", "tools/call"),
				new McpChunkedHttpClient.RequestHeader("Mcp-Name", TOOL_NAME)));
	}

	private static McpChunkedHttpClient listen(int port, String idJson)
			throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
				+ ",\"method\":\"subscriptions/listen\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"notifications\":{\"resourcesListChanged\":true}}}";
		return McpChunkedHttpClient.postMcpMessage(port, body, List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", PROTOCOL_VERSION),
				new McpChunkedHttpClient.RequestHeader(
						"Mcp-Method", "subscriptions/listen")));
	}

	private static void assertJsonHead(
			McpChunkedHttpClient.HttpResponseHead head) {
		Assertions.assertEquals(200, head.status(), head.raw());
		Assertions.assertEquals("application/json",
				head.singleHeader("Content-Type"));
		Assertions.assertTrue(head.hasHeader("Content-Length"));
		Assertions.assertFalse(head.hasHeader("Transfer-Encoding"));
	}

	private static void assertSseHead(
			McpChunkedHttpClient.HttpResponseHead head) {
		Assertions.assertEquals(200, head.status(), head.raw());
		Assertions.assertEquals("text/event-stream",
				head.singleHeader("Content-Type"));
		Assertions.assertEquals("chunked",
				head.singleHeader("Transfer-Encoding"));
		Assertions.assertFalse(head.hasHeader("Content-Length"));
	}

	private static List<McpJsonRpcEnvelope> readAllSseEnvelopes(
			McpChunkedHttpClient client) throws Exception {
		List<McpJsonRpcEnvelope> envelopes = new ArrayList<>();
		byte[] chunk;
		while ((chunk = client.readChunk()) != null) {
			String frame = new String(chunk, StandardCharsets.UTF_8);
			if (frame.startsWith(":"))
				continue;
			envelopes.add(decodeSseFrame(frame));
		}
		return List.copyOf(envelopes);
	}

	private static McpJsonRpcEnvelope readNextSseEnvelope(
			McpChunkedHttpClient client) throws Exception {
		while (true) {
			String frame = client.readChunkText();
			if (!frame.startsWith(":"))
				return decodeSseFrame(frame);
		}
	}

	private static McpJsonRpcEnvelope decodeSseFrame(String frame) {
		Assertions.assertTrue(frame.startsWith("data: "), frame);
		Assertions.assertTrue(frame.endsWith("\n\n"), frame);
		return ENVELOPE_CODEC.decode(frame.substring("data: ".length(),
				frame.length() - 2));
	}

	private static void assertNoIndependentRequests(
			List<McpJsonRpcEnvelope> envelopes) {
		for (McpJsonRpcEnvelope envelope : envelopes)
			Assertions.assertFalse(envelope instanceof McpJsonRpcEnvelope.Request,
					() -> "The server wrote an independent JSON-RPC request: "
							+ envelope);
	}

	private static void assertEmbeddedInputRequest(
			McpJsonRpcEnvelope.ResultResponse response,
			McpJsonRpcId expectedId) {
		Assertions.assertEquals(expectedId, response.id());
		McpJsonObject result = Assertions.assertInstanceOf(
				McpJsonObject.class, response.result());
		Assertions.assertEquals(new McpJsonString("input_required"),
				result.members().get("resultType"));
		McpJsonObject inputRequests = Assertions.assertInstanceOf(
				McpJsonObject.class, result.members().get("inputRequests"));
		McpJsonObject roots = Assertions.assertInstanceOf(
				McpJsonObject.class, inputRequests.members().get("roots"));
		Assertions.assertEquals(new McpJsonString("roots/list"),
				roots.members().get("method"));
		Assertions.assertInstanceOf(McpJsonObject.class,
				roots.members().get("params"));
		Assertions.assertFalse(roots.members().containsKey("id"),
				"Embedded MRTR input requests must not become top-level requests.");
	}

	@ThreadSafe
	private static final class RecordingPublisher
			implements McpSubscriptionEventPublisher {
		@NonNull
		private final CopyOnWriteArrayList<@NonNull Registration> registrations =
				new CopyOnWriteArrayList<>();

		@Override
		@NonNull
		public McpSubscriptionEventRegistration subscribe(
				@NonNull McpSubscriptionEventListener listener) {
			Registration registration = new Registration(listener);
			this.registrations.add(registration);
			return registration;
		}

		@Override
		public void publish(@NonNull McpSubscriptionEvent event) {
			for (Registration registration : this.registrations)
				registration.deliver(event);
		}

		@ThreadSafe
		private final class Registration
				implements McpSubscriptionEventRegistration {
			@NonNull
			private final McpSubscriptionEventListener listener;
			@NonNull
			private final AtomicBoolean open = new AtomicBoolean(true);

			private Registration(
					@NonNull McpSubscriptionEventListener listener) {
				this.listener = listener;
			}

			private void deliver(@NonNull McpSubscriptionEvent event) {
				if (this.open.get())
					this.listener.onEvent(event);
			}

			@Override
			public void close() {
				if (this.open.compareAndSet(true, false))
					registrations.remove(this);
			}
		}
	}

	private static final class RecordingChannel
			implements McpRequestSseStream.Channel {
		@NonNull
		private final AtomicInteger mutations = new AtomicInteger();

		@Override
		@NonNull
		public MicrohttpResponse response(@NonNull List<@NonNull Header> headers) {
			this.mutations.incrementAndGet();
			throw new AssertionError("The rejected request reached the channel.");
		}

		@Override
		public void enqueue(McpRequestSseStream.@NonNull Frame frame) {
			this.mutations.incrementAndGet();
		}

		@Override
		public McpOutboundChannel.@NonNull OfferResult offer(
				McpRequestSseStream.@NonNull Frame frame) {
			this.mutations.incrementAndGet();
			return McpOutboundChannel.OfferResult.ACCEPTED;
		}

		@Override
		public McpOutboundChannel.@NonNull OfferResult offerCoalescing(
				McpRequestSseStream.@NonNull Frame frame,
				@NonNull Object coalescingKey) {
			this.mutations.incrementAndGet();
			return McpOutboundChannel.OfferResult.ACCEPTED;
		}

		@Override
		public boolean complete(
				McpRequestSseStream.@NonNull Frame terminalFrame) {
			this.mutations.incrementAndGet();
			return true;
		}

		@Override
		public boolean fail(@NonNull StreamTerminationReason reason,
				@Nullable Throwable cause) {
			this.mutations.incrementAndGet();
			return true;
		}

		@Override
		public boolean failIfDeadlineExpired(long nowNanos, long deadlineNanos,
				@NonNull StreamTerminationReason reason, @Nullable Throwable cause) {
			this.mutations.incrementAndGet();
			return true;
		}

		@Override
		public boolean failIfWriteIdleExpired(long nowNanos, long timeoutNanos,
				@NonNull StreamTerminationReason reason, @Nullable Throwable cause) {
			this.mutations.incrementAndGet();
			return true;
		}

		@Override
		public long responseWriteIdleDeadlineNanos(long timeoutNanos) {
			this.mutations.incrementAndGet();
			return Long.MAX_VALUE;
		}

		@Override
		public void close(@NonNull StreamTerminationReason reason,
				@Nullable Throwable cause) {
			this.mutations.incrementAndGet();
		}

		@Override
		@NonNull
		public Optional<McpOutboundChannel.@NonNull Snapshot> snapshot() {
			this.mutations.incrementAndGet();
			return Optional.empty();
		}

		@Override
		public boolean isTerminalWritten() {
			this.mutations.incrementAndGet();
			return false;
		}

		private int mutations() {
			return this.mutations.get();
		}
	}
}
