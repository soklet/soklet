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

package com.soklet;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Public-API evidence for the JSON-RPC notification identifier boundary.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(30)
public class McpNotificationPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp/notification-boundary";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final Duration WAIT = Duration.ofSeconds(5);

	@Test
	public void inboundNotificationsNeverEmitJsonRpcBodiesOrReachApplicationHandlers() {
		AtomicInteger handlerCalls = new AtomicInteger();
		AtomicInteger interceptorCalls = new AtomicInteger();
		AtomicInteger limiterCalls = new AtomicInteger();
		List<McpAdmissionContext> admissionContexts =
				new CopyOnWriteArrayList<>();
		List<String> stageTrace = new CopyOnWriteArrayList<>();
		McpAdmissionRejection admissionRejection = McpAdmissionRejection
				.withStatusCodeAndError(401, McpJsonRpcError.fromApplication(1_001,
						"Notification admission rejected"))
				.header("WWW-Authenticate", "Bearer realm=soklet-mcp")
				.build();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("must-not-run")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					handlerCalls.incrementAndGet();
					return McpCompleteResult.fromToolText("unexpected");
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"notification-boundary-test", "3.6.0-SNAPSHOT").build())
				.tool(tool)
				.build();
		McpServer server = baseServerBuilder(endpoint)
				.admissionController(context -> {
					admissionContexts.add(context);
					String caseName = caseName(context.getRequest());
					stageTrace.add("admission:" + caseName);
					if (caseName.equals("admission-rejected"))
						return McpAdmissionDecision.rejected(admissionRejection);
					return McpAdmissionDecision.accepted();
				})
				.requestRateLimiter(context -> {
					limiterCalls.incrementAndGet();
					String caseName = caseName(context.getRequest());
					stageTrace.add("limiter:" + caseName);
					if (caseName.equals("rate-limited"))
						return McpRateLimitDecision.denied(Duration.ofMillis(1));
					return McpRateLimitDecision.allowed();
				})
				.handlerInterceptor((context, continuation) -> {
					interceptorCalls.incrementAndGet();
					return continuation.proceed();
				})
				.build();
		SokletConfig config = config(server);
		List<InboundCase> cases = List.of(
				new InboundCase("accepted-cancellation",
						"notifications/cancelled",
						"{\"requestId\":\"missing-target\"}", 202,
						Map.of("Cache-Control", Set.of("no-store"))),
				new InboundCase("request-shaped-tools-notification",
						"tools/call",
						"{\"_meta\":{"
								+ "\"io.modelcontextprotocol/protocolVersion\":\""
								+ PROTOCOL_VERSION + "\","
								+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
								+ "\"name\":\"must-not-run\",\"arguments\":{}}",
						400,
						Map.of("Cache-Control", Set.of("no-store"))),
				new InboundCase("unsupported-notification",
						"vendor.example/future-notification", null, 400,
						Map.of("Cache-Control", Set.of("no-store"))),
				new InboundCase("admission-rejected",
						"vendor.example/admission-notification", null, 401,
						Map.of("Cache-Control", Set.of("no-store"),
								"WWW-Authenticate", Set.of(
										"Bearer realm=soklet-mcp"))),
				new InboundCase("rate-limited",
						"vendor.example/rate-notification", null, 429,
						Map.of("Cache-Control", Set.of("no-store"),
								"Retry-After", Set.of("1"))));

		Soklet.runSimulator(config, simulator -> {
			for (InboundCase testCase : cases) {
				try (McpSimulation simulation = simulator.startMcpRequest(
						notification(testCase))) {
					McpSimulationResponse response = awaitResponse(simulation);
					Assertions.assertEquals(testCase.expectedStatus(),
							response.getStatusCode(), testCase.name());
					Assertions.assertEquals(McpSimulationBodyMode.EMPTY,
							response.getBodyMode(), testCase.name());
					byte[] body = response.getBody().orElseThrow();
					Assertions.assertArrayEquals(new byte[0], body, testCase.name());
					String text = new String(body, StandardCharsets.UTF_8);
					Assertions.assertFalse(text.contains("\"jsonrpc\""),
							testCase.name());
					Assertions.assertFalse(text.contains("\"id\""), testCase.name());
					Assertions.assertEquals(testCase.expectedHeaders(),
							response.getHeaders(), testCase.name());

					McpSimulationCompletion completion = awaitCompletion(simulation);
					Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
							completion.getReason(), testCase.name());
					Assertions.assertTrue(completion.getTerminalMessage().isEmpty(),
							testCase.name());
					Assertions.assertTrue(completion.getThrowables().isEmpty(),
							testCase.name());
					Assertions.assertTrue(nextItem(simulation, Duration.ZERO).isEmpty(),
							testCase.name());
				}
			}
		});

		Assertions.assertEquals(5, admissionContexts.size());
		for (McpAdmissionContext context : admissionContexts) {
			Assertions.assertTrue(context.isNotification());
			Assertions.assertTrue(context.getRequestId().isEmpty());
		}
		Assertions.assertEquals(4, limiterCalls.get(),
				"Admission rejection must precede request limiting.");
		Assertions.assertEquals(List.of(
				"admission:accepted-cancellation",
				"limiter:accepted-cancellation",
				"admission:request-shaped-tools-notification",
				"limiter:request-shaped-tools-notification",
				"admission:unsupported-notification",
				"limiter:unsupported-notification",
				"admission:admission-rejected",
				"admission:rate-limited",
				"limiter:rate-limited"), stageTrace);
		Assertions.assertEquals(0, interceptorCalls.get());
		Assertions.assertEquals(0, handlerCalls.get());
		assertStopped(server);
	}

	@Test
	public void outboundFrameworkNotificationsOmitIdsWhileTerminalResponsePreservesRequestId() {
		String requestId = "notification-boundary-request";
		McpLocalSubscriptionEventPublisher publisher =
				McpLocalSubscriptionEventPublisher.fromDefaults();
		AtomicInteger handlerCalls = new AtomicInteger();
		AtomicInteger interceptorCalls = new AtomicInteger();
		McpToolRegistration<McpJsonObject> progressTool = McpToolRegistration
				.withName("emit-progress")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					handlerCalls.incrementAndGet();
					features.require(McpProgressReporter.class).report(
							McpProgressUpdate.withProgress(1.0d).build());
					return McpCompleteResult.fromToolText("complete");
				})
				.build();
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisher(publisher)
				.notificationTypes(EnumSet.of(
						McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED))
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"notification-output-test", "3.6.0-SNAPSHOT").build())
				.tool(progressTool)
				.resource(McpResourceRegistration.withUriAndName(
						URI.create("https://example.com/notification-resource"),
						"Notification resource")
						.handler((request, read, features) ->
								McpCompleteResult.fromResourceOutput(
										McpResourceOutput.builder()
												.content(McpTextResourceContents
														.withUriAndText(read.getUri(),
																"unused")
														.build())
												.build()))
						.build())
				.subscriptions(subscriptions)
				.build();
		McpServer server = baseServerBuilder(endpoint)
				.admissionController(McpAdmissionController.acceptAllInstance())
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.handlerInterceptor((context, continuation) -> {
					interceptorCalls.incrementAndGet();
					return continuation.proceed();
				})
				.build();

		Soklet.runSimulator(config(server), simulator -> {
			try (McpSimulation progress = simulator.startMcpRequest(
					progressRequest(requestId))) {
				assertSseResponse(awaitResponse(progress));
				McpSimulationStreamItem progressNotification = awaitNextItem(progress);
				assertNotification(progressNotification, "notifications/progress",
						"data: {\"jsonrpc\":\"2.0\","
								+ "\"method\":\"notifications/progress\","
								+ "\"params\":{\"progressToken\":"
								+ "\"opaque-progress-token\",\"progress\":1}}\n\n");
				McpJsonObject progressParameters = objectMember(
						messageObject(progressNotification), "params");
				Assertions.assertEquals(McpJsonString.fromValue(
						"opaque-progress-token"),
						progressParameters.find("progressToken").orElseThrow());

				McpSimulationStreamItem terminal = awaitNextItem(progress);
				Assertions.assertEquals(McpSimulationStreamItemType.JSON_MESSAGE,
						terminal.getType());
				Assertions.assertTrue(terminal.getComment().isEmpty());
				McpJsonObject terminalMessage = messageObject(terminal);
				Assertions.assertEquals(List.of("jsonrpc", "id", "result"),
						List.copyOf(terminalMessage.getMembers().keySet()));
				Assertions.assertEquals(McpJsonString.fromValue("2.0"),
						terminalMessage.find("jsonrpc").orElseThrow());
				Assertions.assertTrue(terminalMessage.find("method").isEmpty());
				Assertions.assertEquals(McpJsonString.fromValue(requestId),
						terminalMessage.find("id").orElseThrow());
				Assertions.assertTrue(terminalMessage.find("result").isPresent());
				Assertions.assertEquals("data: {\"jsonrpc\":\"2.0\","
						+ "\"id\":\"notification-boundary-request\","
						+ "\"result\":{\"content\":[{\"type\":\"text\","
						+ "\"text\":\"complete\"}],\"resultType\":\"complete\"}}\n\n",
						new String(terminal.getEncodedBytes(), StandardCharsets.UTF_8));
				McpSimulationCompletion completion = awaitCompletion(progress);
				Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
						completion.getReason());
				McpJsonObject completionTerminal = Assertions.assertInstanceOf(
						McpJsonObject.class,
						completion.getTerminalMessage().orElseThrow());
				Assertions.assertEquals(List.of("jsonrpc", "id", "result"),
						List.copyOf(completionTerminal.getMembers().keySet()));
				Assertions.assertEquals(McpJsonString.fromValue("2.0"),
						completionTerminal.find("jsonrpc").orElseThrow());
				Assertions.assertEquals(McpJsonString.fromValue(requestId),
						completionTerminal.find("id").orElseThrow());
				Assertions.assertTrue(completionTerminal.find("method").isEmpty());
				Assertions.assertTrue(completionTerminal.find("result").isPresent());
				Assertions.assertTrue(completion.getThrowables().isEmpty());
				Assertions.assertTrue(nextItem(progress, Duration.ZERO).isEmpty());
			}

			try (McpSimulation subscription = simulator.startMcpRequest(
					subscriptionRequest("notification-subscription"))) {
				assertSseResponse(awaitResponse(subscription));
				McpSimulationStreamItem acknowledgment =
						awaitNextItem(subscription);
				assertNotification(acknowledgment,
						"notifications/subscriptions/acknowledged",
						"data: {\"jsonrpc\":\"2.0\","
								+ "\"method\":\"notifications/subscriptions/acknowledged\","
								+ "\"params\":{\"_meta\":{"
								+ "\"io.modelcontextprotocol/subscriptionId\":"
								+ "\"notification-subscription\"},"
								+ "\"notifications\":{\"resourcesListChanged\":true}}}\n\n");
				assertSubscriptionId(acknowledgment, "notification-subscription");
				publisher.publishResourcesListChanged();
				McpSimulationStreamItem listChanged = awaitNextItem(subscription);
				assertNotification(listChanged,
						"notifications/resources/list_changed",
						"data: {\"jsonrpc\":\"2.0\","
								+ "\"method\":\"notifications/resources/list_changed\","
								+ "\"params\":{\"_meta\":{"
								+ "\"io.modelcontextprotocol/subscriptionId\":"
								+ "\"notification-subscription\"}}}\n\n");
				assertSubscriptionId(listChanged, "notification-subscription");
				subscription.cancel();
				McpSimulationCompletion completion = awaitCompletion(subscription);
				Assertions.assertEquals(
						McpStreamTerminationReason.CLIENT_DISCONNECTED,
						completion.getReason());
				Assertions.assertTrue(completion.getTerminalMessage().isEmpty());
				Assertions.assertTrue(completion.getThrowables().isEmpty());
				Assertions.assertTrue(nextItem(subscription,
						Duration.ZERO).isEmpty());
			}
		});

		Assertions.assertEquals(1, handlerCalls.get());
		Assertions.assertEquals(1, interceptorCalls.get());
		assertStopped(server);
	}

	private static McpServer.Builder baseServerBuilder(
			@NonNull McpEndpoint endpoint) {
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.acceptAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
	}

	@NonNull
	private static SokletConfig config(@NonNull McpServer server) {
		return SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build();
	}

	@NonNull
	private static Request notification(@NonNull InboundCase testCase) {
		String body = "{\"jsonrpc\":\"2.0\",\"method\":\""
				+ testCase.method() + "\""
				+ (testCase.params() == null ? ""
						: ",\"params\":" + testCase.params()) + "}";
		Map<String, Set<String>> headers = baseHeaders();
		headers.put("Mcp-Method", Set.of(testCase.method()));
		if (testCase.method().equals("tools/call"))
			headers.put("Mcp-Name", Set.of("must-not-run"));
		headers.put("X-Notification-Case", Set.of(testCase.name()));
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	@NonNull
	private static Request progressRequest(@NonNull String requestId) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{},"
				+ "\"progressToken\":\"opaque-progress-token\"},"
				+ "\"name\":\"emit-progress\",\"arguments\":{}}}";
		Map<String, Set<String>> headers = baseHeaders();
		headers.put("Mcp-Method", Set.of("tools/call"));
		headers.put("Mcp-Name", Set.of("emit-progress"));
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	@NonNull
	private static Request subscriptionRequest(@NonNull String requestId) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"subscriptions/listen\",\"params\":{"
				+ "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"notifications\":{\"resourcesListChanged\":true}}}";
		Map<String, Set<String>> headers = baseHeaders();
		headers.put("Mcp-Method", Set.of("subscriptions/listen"));
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	@NonNull
	private static Map<String, Set<String>> baseHeaders() {
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("Host", Set.of(LOOPBACK + ":0"));
		headers.put("Content-Type", Set.of(JSON_MEDIA_TYPE + "; charset=UTF-8"));
		headers.put("Accept", Set.of(JSON_MEDIA_TYPE + ", text/event-stream"));
		headers.put("MCP-Protocol-Version", Set.of(PROTOCOL_VERSION));
		return headers;
	}

	@NonNull
	private static String caseName(@NonNull Request request) {
		return request.getHeaders().entrySet().stream()
				.filter(entry -> entry.getKey().equalsIgnoreCase(
						"X-Notification-Case"))
				.findFirst()
				.flatMap(entry -> entry.getValue().stream().findFirst())
				.orElseThrow();
	}

	private static void assertSseResponse(
			@NonNull McpSimulationResponse response) {
		Assertions.assertEquals(200, response.getStatusCode());
		Assertions.assertEquals(List.of("Content-Type", "Cache-Control",
				"X-Accel-Buffering"),
				List.copyOf(response.getHeaders().keySet()));
		Assertions.assertEquals(Map.of(
				"Content-Type", Set.of("text/event-stream"),
				"Cache-Control", Set.of("no-store"),
				"X-Accel-Buffering", Set.of("no")), response.getHeaders());
		Assertions.assertEquals(McpSimulationBodyMode.SERVER_SENT_EVENTS,
				response.getBodyMode());
		Assertions.assertTrue(response.getBody().isEmpty());
	}

	private static void assertNotification(
			@NonNull McpSimulationStreamItem item,
			@NonNull String expectedMethod, @NonNull String expectedFrame) {
		Assertions.assertEquals(McpSimulationStreamItemType.JSON_MESSAGE,
				item.getType());
		Assertions.assertTrue(item.getComment().isEmpty());
		McpJsonObject message = messageObject(item);
		Assertions.assertEquals(List.of("jsonrpc", "method", "params"),
				List.copyOf(message.getMembers().keySet()));
		Assertions.assertEquals(McpJsonString.fromValue("2.0"),
				message.find("jsonrpc").orElseThrow());
		Assertions.assertEquals(McpJsonString.fromValue(expectedMethod),
				message.find("method").orElseThrow());
		Assertions.assertTrue(message.find("params").isPresent());
		Assertions.assertTrue(message.find("id").isEmpty(),
				"JSON-RPC notification frames must omit top-level id: "
						+ new String(item.getEncodedBytes(), StandardCharsets.UTF_8));
		Assertions.assertEquals(expectedFrame,
				new String(item.getEncodedBytes(), StandardCharsets.UTF_8));
	}

	private static void assertSubscriptionId(
			@NonNull McpSimulationStreamItem item,
			@NonNull String expectedSubscriptionId) {
		McpJsonObject parameters = objectMember(messageObject(item), "params");
		McpJsonObject metadata = objectMember(parameters, "_meta");
		Assertions.assertEquals(McpJsonString.fromValue(expectedSubscriptionId),
				metadata.find("io.modelcontextprotocol/subscriptionId")
						.orElseThrow());
	}

	@NonNull
	private static McpJsonObject objectMember(@NonNull McpJsonObject object,
			@NonNull String name) {
		return Assertions.assertInstanceOf(McpJsonObject.class,
				object.find(name).orElseThrow());
	}

	@NonNull
	private static McpJsonObject messageObject(
			@NonNull McpSimulationStreamItem item) {
		return Assertions.assertInstanceOf(McpJsonObject.class,
				item.getMessage().orElseThrow());
	}

	@NonNull
	private static McpSimulationResponse awaitResponse(
			@NonNull McpSimulation simulation) {
		try {
			return simulation.awaitResponse(WAIT).orElseThrow(() ->
					new AssertionError("Timed out awaiting simulator response."));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	@NonNull
	private static McpSimulationStreamItem awaitNextItem(
			@NonNull McpSimulation simulation) {
		return nextItem(simulation, WAIT).orElseThrow(() ->
				new AssertionError("Timed out awaiting simulator stream item."));
	}

	@NonNull
	private static Optional<McpSimulationStreamItem> nextItem(
			@NonNull McpSimulation simulation, @NonNull Duration timeout) {
		try {
			return simulation.nextStreamItem(timeout);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	@NonNull
	private static McpSimulationCompletion awaitCompletion(
			@NonNull McpSimulation simulation) {
		try {
			return simulation.awaitCompletion(WAIT).orElseThrow(() ->
					new AssertionError("Timed out awaiting simulator completion."));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	private static void assertStopped(@NonNull McpServer server) {
		Assertions.assertEquals(McpServerStatus.STOPPED,
				server.getDiagnostics().getStatus());
		Assertions.assertTrue(server.getDiagnostics().getBoundAddress().isEmpty());
		Assertions.assertEquals(0,
				server.getDiagnostics().getActiveHandlerExecutions());
		Assertions.assertEquals(0, server.getDiagnostics().getQueuedRequests());
		Assertions.assertEquals(0,
				server.getDiagnostics().getActiveRequestStreams());
		Assertions.assertEquals(0,
				server.getDiagnostics().getActiveSubscriptions());
	}

	private record InboundCase(@NonNull String name, @NonNull String method,
			String params, int expectedStatus,
			@NonNull Map<@NonNull String, @NonNull Set<@NonNull String>>
					expectedHeaders) {
		private InboundCase {
			expectedHeaders = Map.copyOf(expectedHeaders);
		}
	}
}
