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

import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Every-operation, off-network conformance coverage for the public MCP
 * simulator.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(30)
public class McpSimulatorEveryOperationTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String TOOL_NAME = "matrix.tool";
	private static final String PROMPT_NAME = "matrix.prompt";
	private static final String RESOURCE_URI = "matrix://resource/text";
	private static final String TEMPLATE_URI = "matrix://records/{id}";
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final McpJsonCodec JSON_CODEC =
			new McpJsonCodec(McpJsonLimits.productionDefaults());
	private static final List<OperationCase> OPERATIONS = List.of(
			new OperationCase("server/discover", "every-discover", null, "",
					List.of("\"capabilities\":",
							"\"io.modelcontextprotocol/serverInfo\":{",
							"\"name\":\"simulator-every-operation-test\""),
					List.of()),
			new OperationCase("tools/list", "every-tools-list", null, "",
					List.of("\"tools\":[{", "\"name\":\"" + TOOL_NAME + "\"",
							"\"resultType\":\"complete\""), List.of()),
			new OperationCase("tools/call", "every-tools-call", TOOL_NAME,
					",\"name\":\"" + TOOL_NAME + "\",\"arguments\":{}",
					List.of("\"text\":\"matrix tool complete\"",
							"\"resultType\":\"complete\""), List.of()),
			new OperationCase("prompts/list", "every-prompts-list", null, "",
					List.of("\"prompts\":[{", "\"name\":\"" + PROMPT_NAME + "\"",
							"\"resultType\":\"complete\""), List.of()),
			new OperationCase("prompts/get", "every-prompts-get", PROMPT_NAME,
					",\"name\":\"" + PROMPT_NAME + "\",\"arguments\":{}",
					List.of("\"description\":\"Matrix prompt\"",
							"\"text\":\"matrix prompt complete\"",
							"\"resultType\":\"complete\""), List.of()),
			new OperationCase("resources/list", "every-resources-list", null, "",
					List.of("\"resources\":[{", "\"uri\":\"" + RESOURCE_URI + "\"",
							"\"resultType\":\"complete\""),
					List.of("\"uriTemplate\"", TEMPLATE_URI)),
			new OperationCase("resources/templates/list", "every-templates-list",
					null, "", List.of("\"resourceTemplates\":[{",
							"\"uriTemplate\":\"" + TEMPLATE_URI + "\"",
							"\"resultType\":\"complete\""),
					List.of("\"uri\":\"" + RESOURCE_URI + "\"")),
			new OperationCase("resources/read", "every-resources-read", RESOURCE_URI,
					",\"uri\":\"" + RESOURCE_URI + "\"",
					List.of("\"uri\":\"" + RESOURCE_URI + "\"",
							"\"text\":\"matrix resource complete\"",
							"\"resultType\":\"complete\""), List.of()),
			new OperationCase("subscriptions/listen", "every-subscription", null,
					",\"notifications\":{\"resourcesListChanged\":true}",
					List.of(), List.of()));

	@TestFactory
	public Stream<DynamicTest> recognizedRequestMethodsReplayExactJsonOrSseShapes() {
		return OPERATIONS.stream().map(operation -> DynamicTest.dynamicTest(
				operation.method(), () -> {
			Fixture fixture = new Fixture(1, false);
			Soklet.runSimulator(fixture.config(), simulator -> {
				String transcript = replay(simulator, fixture, operation);
				Assertions.assertTrue(transcript.contains(operation.id()), transcript);
				assertStoppedDiagnostics(fixture.server());
			});
			fixture.assertFinished(Map.of(operation.method(),
					operation.isSubscription()
							? McpRequestOutcome.CLIENT_DISCONNECTED
							: McpRequestOutcome.COMPLETE));
			fixture.assertOffNetwork();
		}));
	}

	@Test
	public void cancellationNotificationIsAcceptedAndIgnoredWithoutTerminatingItsTargetSimulation() {
		Fixture fixture = new Fixture(2, true);

		try {
			Soklet.runSimulator(fixture.config(), simulator -> {
				McpSimulation target = simulator.startMcpRequest(
						request(new OperationCase("tools/call", "cancel-target",
								TOOL_NAME, ",\"name\":\"" + TOOL_NAME
										+ "\",\"arguments\":{}", List.of(), List.of())));
				Assertions.assertTrue(awaitLatch(fixture.blockingToolEntered()));
				Assertions.assertFalse(target.isComplete());

				McpSimulation notification = simulator.startMcpRequest(
						cancellationNotification("cancel-target"));
				McpSimulationResponse response = awaitResponse(notification);
				Assertions.assertEquals(202, response.getStatusCode());
				Assertions.assertEquals(McpSimulationBodyMode.EMPTY,
						response.getBodyMode());
				Assertions.assertArrayEquals(new byte[0],
						response.getBody().orElseThrow());
				Assertions.assertEquals(Map.of("Cache-Control", Set.of("no-store")),
						response.getHeaders());
				Assertions.assertEquals(List.of("Cache-Control"),
						List.copyOf(response.getHeaders().keySet()));
				McpSimulationCompletion notificationCompletion =
						awaitCompletion(notification);
				Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
						notificationCompletion.getReason());
				Assertions.assertTrue(
						notificationCompletion.getTerminalMessage().isEmpty());
				Assertions.assertTrue(notificationCompletion.getThrowables().isEmpty());
				Assertions.assertTrue(pollNextItem(notification, Duration.ZERO).isEmpty());
				Assertions.assertTrue(fixture.metrics()
						.awaitFinishedMethod("notifications/cancelled"));

				Assertions.assertFalse(target.isComplete(),
						"The compatibility notification must not terminate its target.");
				Assertions.assertEquals(1, fixture.handlerCalls().get());
				Assertions.assertEquals(1, fixture.interceptorCalls().get());
				Assertions.assertEquals(1L, fixture.cancelObserved().getCount());
				Assertions.assertTrue(fixture.metrics().events().stream().noneMatch(
						McpMetricsEvent.CancelationSignaled.class::isInstance),
						fixture.metrics().events().toString());

				target.cancel();
				Assertions.assertTrue(awaitLatch(fixture.cancelObserved()));
				Assertions.assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED,
						awaitCompletion(target).getReason());
				fixture.releaseBlockingTool().countDown();
				Assertions.assertTrue(pollNextItem(target, Duration.ZERO).isEmpty());
				assertStoppedDiagnostics(fixture.server());
			});
		} finally {
			fixture.releaseBlockingTool().countDown();
		}

		fixture.assertFinished(Map.of(
				"notifications/cancelled", McpRequestOutcome.COMPLETE,
				"tools/call", McpRequestOutcome.CLIENT_DISCONNECTED));
		fixture.assertOffNetwork();
	}

	@Test
	public void concurrentRecognizedOperationReplayIsIsolatedAndExactlyDrained()
			throws Exception {
		Fixture fixture = new Fixture(OPERATIONS.size(), false);
		ExecutorService executor = Executors.newFixedThreadPool(OPERATIONS.size());
		CountDownLatch ready = new CountDownLatch(OPERATIONS.size());
		CountDownLatch start = new CountDownLatch(1);
		List<Future<ReplayResult>> futures = new CopyOnWriteArrayList<>();

		try {
			Soklet.runSimulator(fixture.config(), simulator -> {
				for (OperationCase operation : OPERATIONS)
					futures.add(executor.submit(() -> {
						ready.countDown();
						Assertions.assertTrue(awaitLatch(start));
						return new ReplayResult(operation,
								replay(simulator, fixture, operation));
					}));
				Assertions.assertTrue(awaitLatch(ready));
				start.countDown();

				List<ReplayResult> results = futures.stream().map(future -> {
					try {
						return future.get(5, TimeUnit.SECONDS);
					} catch (Exception e) {
						throw new AssertionError(e);
					}
				}).toList();
				for (ReplayResult result : results) {
					Assertions.assertTrue(result.transcript().contains(
							result.operation().id()), result.transcript());
					for (OperationCase other : OPERATIONS)
						if (other != result.operation())
							Assertions.assertFalse(result.transcript().contains(other.id()),
									result.transcript());
				}
				assertStoppedDiagnostics(fixture.server());
			});
		} finally {
			start.countDown();
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}

		Map<String, McpRequestOutcome> outcomes = new LinkedHashMap<>();
		for (OperationCase operation : OPERATIONS)
			outcomes.put(operation.method(), operation.isSubscription()
					? McpRequestOutcome.CLIENT_DISCONNECTED
					: McpRequestOutcome.COMPLETE);
		fixture.assertFinished(outcomes);
		fixture.assertOffNetwork();
	}

	@NonNull
	private static String replay(@NonNull Simulator simulator,
			@NonNull Fixture fixture, @NonNull OperationCase operation) {
		McpSimulation simulation = simulator.startMcpRequest(request(operation));
		McpSimulationResponse response = awaitResponse(simulation);
		Assertions.assertEquals(200, response.getStatusCode());
		if (operation.isSubscription())
			return replaySubscription(simulation, response, fixture.publisher(),
					operation.id());

		Assertions.assertEquals(McpSimulationBodyMode.JSON, response.getBodyMode());
		Assertions.assertEquals(Map.of(
				"Cache-Control", Set.of("no-store"),
				"Content-Type", Set.of(JSON_MEDIA_TYPE)), response.getHeaders());
		Assertions.assertEquals(List.of("Cache-Control", "Content-Type"),
				List.copyOf(response.getHeaders().keySet()));
		byte[] body = response.getBody().orElseThrow();
		assertCanonicalJson(body);
		String json = new String(body, StandardCharsets.UTF_8);
		Assertions.assertTrue(json.startsWith("{\"jsonrpc\":\"2.0\",\"id\":\""
				+ operation.id() + "\",\"result\":{"), json);
		Assertions.assertTrue(json.endsWith("}}"), json);
		for (String fragment : operation.requiredFragments())
			Assertions.assertTrue(json.contains(fragment), json);
		for (String fragment : operation.forbiddenFragments())
			Assertions.assertFalse(json.contains(fragment), json);
		Assertions.assertEquals(expectedJson(operation), json);
		McpSimulationCompletion completion = awaitCompletion(simulation);
		Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
				completion.getReason());
		Assertions.assertTrue(completion.getTerminalMessage().isEmpty());
		Assertions.assertTrue(completion.getThrowables().isEmpty());
		Assertions.assertTrue(pollNextItem(simulation, Duration.ZERO).isEmpty());
		return json;
	}

	@NonNull
	private static String expectedJson(@NonNull OperationCase operation) {
		String prefix = "{\"jsonrpc\":\"2.0\",\"id\":\"" + operation.id()
				+ "\",\"result\":";
		String result = switch (operation.method()) {
			case "server/discover" -> "{\"supportedVersions\":[\"2026-07-28\"],"
					+ "\"capabilities\":{\"tools\":{},\"prompts\":{},"
					+ "\"resources\":{\"listChanged\":true}},\"ttlMs\":0,"
					+ "\"cacheScope\":\"private\",\"resultType\":\"complete\","
					+ "\"_meta\":{\"io.modelcontextprotocol/serverInfo\":{"
					+ "\"name\":\"simulator-every-operation-test\","
					+ "\"version\":\"3.6.0-SNAPSHOT\"}}}";
			case "tools/list" -> "{\"tools\":[{\"name\":\"" + TOOL_NAME
					+ "\",\"inputSchema\":{\"type\":\"object\"}}],\"ttlMs\":0,"
					+ "\"cacheScope\":\"private\",\"resultType\":\"complete\"}";
			case "tools/call" -> "{\"content\":[{\"type\":\"text\","
					+ "\"text\":\"matrix tool complete\"}],"
					+ "\"resultType\":\"complete\"}";
			case "prompts/list" -> "{\"prompts\":[{\"name\":\""
					+ PROMPT_NAME + "\"}],\"ttlMs\":0,\"cacheScope\":\"private\","
					+ "\"resultType\":\"complete\"}";
			case "prompts/get" -> "{\"description\":\"Matrix prompt\","
					+ "\"messages\":[{\"role\":\"user\",\"content\":{"
					+ "\"type\":\"text\",\"text\":\"matrix prompt complete\"}}],"
					+ "\"resultType\":\"complete\"}";
			case "resources/list" -> "{\"resources\":[{\"uri\":\""
					+ RESOURCE_URI + "\",\"name\":\"Matrix resource\"}],"
					+ "\"ttlMs\":0,\"cacheScope\":\"private\","
					+ "\"resultType\":\"complete\"}";
			case "resources/templates/list" -> "{\"resourceTemplates\":[{"
					+ "\"uriTemplate\":\"" + TEMPLATE_URI + "\","
					+ "\"name\":\"Matrix template\"}],\"ttlMs\":0,"
					+ "\"cacheScope\":\"private\",\"resultType\":\"complete\"}";
			case "resources/read" -> "{\"contents\":[{\"uri\":\""
					+ RESOURCE_URI + "\",\"text\":\"matrix resource complete\"}],"
					+ "\"cacheScope\":\"private\",\"ttlMs\":0,"
					+ "\"resultType\":\"complete\"}";
			default -> throw new IllegalArgumentException(
					"No JSON expectation for " + operation.method());
		};
		return prefix + result + "}";
	}

	@NonNull
	private static String replaySubscription(@NonNull McpSimulation simulation,
			@NonNull McpSimulationResponse response,
			@NonNull McpLocalSubscriptionEventPublisher publisher,
			@NonNull String id) {
		Assertions.assertEquals(McpSimulationBodyMode.SERVER_SENT_EVENTS,
				response.getBodyMode());
		Assertions.assertTrue(response.getBody().isEmpty());
		Assertions.assertEquals(Map.of(
				"Content-Type", Set.of("text/event-stream"),
				"Cache-Control", Set.of("no-store"),
				"X-Accel-Buffering", Set.of("no")), response.getHeaders());
		Assertions.assertEquals(List.of(
				"Content-Type", "Cache-Control", "X-Accel-Buffering"),
				List.copyOf(response.getHeaders().keySet()));

		McpSimulationStreamItem acknowledgmentItem = nextItem(simulation);
		Assertions.assertEquals(McpSimulationStreamItemType.JSON_MESSAGE,
				acknowledgmentItem.getType());
		Assertions.assertTrue(acknowledgmentItem.getMessage().isPresent());
		Assertions.assertTrue(acknowledgmentItem.getComment().isEmpty());
		String acknowledgment = new String(acknowledgmentItem.getEncodedBytes(),
				StandardCharsets.UTF_8);
		String expectedAcknowledgment = "data: {\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/subscriptions/acknowledged\","
				+ "\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/subscriptionId\":\"" + id + "\"},"
				+ "\"notifications\":{\"resourcesListChanged\":true}}}\n\n";
		Assertions.assertEquals(expectedAcknowledgment, acknowledgment);
		assertCanonicalSseFrame(acknowledgment);

		publisher.publishResourcesListChanged();
		McpSimulationStreamItem eventItem = nextItem(simulation);
		Assertions.assertEquals(McpSimulationStreamItemType.JSON_MESSAGE,
				eventItem.getType());
		Assertions.assertTrue(eventItem.getMessage().isPresent());
		Assertions.assertTrue(eventItem.getComment().isEmpty());
		String event = new String(eventItem.getEncodedBytes(),
				StandardCharsets.UTF_8);
		String expectedEvent = "data: {\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/resources/list_changed\","
				+ "\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/subscriptionId\":\"" + id
				+ "\"}}}\n\n";
		Assertions.assertEquals(expectedEvent, event);
		assertCanonicalSseFrame(event);

		simulation.cancel();
		McpSimulationCompletion completion = awaitCompletion(simulation);
		Assertions.assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED,
				completion.getReason());
		Assertions.assertTrue(completion.getTerminalMessage().isEmpty());
		Assertions.assertTrue(completion.getThrowables().isEmpty());
		Assertions.assertTrue(pollNextItem(simulation, Duration.ZERO).isEmpty());
		return acknowledgment + event;
	}

	private static void assertCanonicalJson(byte @NonNull [] bytes) {
		Assertions.assertArrayEquals(bytes,
				JSON_CODEC.toUtf8Bytes(JSON_CODEC.parse(bytes)));
	}

	private static void assertCanonicalSseFrame(@NonNull String frame) {
		Assertions.assertTrue(frame.startsWith("data: "), frame);
		Assertions.assertTrue(frame.endsWith("\n\n"), frame);
		byte[] json = frame.substring("data: ".length(), frame.length() - 2)
				.getBytes(StandardCharsets.UTF_8);
		assertCanonicalJson(json);
	}

	@NonNull
	private static Request request(@NonNull OperationCase operation) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + operation.id()
				+ "\",\"method\":\"" + operation.method() + "\",\"params\":{"
				+ "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}"
				+ operation.paramsSuffix() + "}}";
		Map<String, Set<String>> headers = baseHeaders();
		headers.put("Mcp-Method", Set.of(operation.method()));
		if (operation.operationName() != null)
			headers.put("Mcp-Name", Set.of(operation.operationName()));
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	@NonNull
	private static Request cancellationNotification(@NonNull String targetId) {
		String body = "{\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/cancelled\","
				+ "\"params\":{\"requestId\":\"" + targetId + "\"}}";
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(baseHeaders())
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

	private static void assertStoppedDiagnostics(@NonNull McpServer server) {
		McpServerDiagnostics diagnostics = server.getDiagnostics();
		Assertions.assertEquals(McpServerStatus.STOPPED, diagnostics.getStatus());
		Assertions.assertTrue(diagnostics.getBoundAddress().isEmpty());
		Assertions.assertEquals(0, diagnostics.getActiveHandlerExecutions());
		Assertions.assertEquals(0, diagnostics.getQueuedRequests());
		Assertions.assertEquals(0, diagnostics.getActiveRequestStreams());
		Assertions.assertEquals(0, diagnostics.getActiveSubscriptions());
		Assertions.assertFalse(server.isStarted());
	}

	@NonNull
	private static McpSimulationResponse awaitResponse(
			@NonNull McpSimulation simulation) {
		try {
			return simulation.awaitResponse(WAIT).orElseThrow(() ->
					new AssertionError("Timed out awaiting simulator response."));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	@NonNull
	private static McpSimulationStreamItem nextItem(
			@NonNull McpSimulation simulation) {
		try {
			return simulation.nextStreamItem(WAIT).orElseThrow(() ->
					new AssertionError("Timed out awaiting simulator stream item."));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	@NonNull
	private static McpSimulationCompletion awaitCompletion(
			@NonNull McpSimulation simulation) {
		try {
			return simulation.awaitCompletion(WAIT).orElseThrow(() ->
					new AssertionError("Timed out awaiting simulator completion."));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	@NonNull
	private static Optional<@NonNull McpSimulationStreamItem> pollNextItem(
			@NonNull McpSimulation simulation, @NonNull Duration timeout) {
		try {
			return simulation.nextStreamItem(timeout);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static boolean awaitLatch(@NonNull CountDownLatch latch) {
		try {
			return latch.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private record OperationCase(@NonNull String method, @NonNull String id,
			@Nullable String operationName, @NonNull String paramsSuffix,
			@NonNull List<@NonNull String> requiredFragments,
			@NonNull List<@NonNull String> forbiddenFragments) {
		private boolean isSubscription() {
			return this.method.equals("subscriptions/listen");
		}
	}

	private record ReplayResult(@NonNull OperationCase operation,
			@NonNull String transcript) {
	}

	private record FinishedObservation(@NonNull McpRequestContext context,
			@NonNull McpRequestOutcome outcome,
			@Nullable McpJsonRpcError error, @NonNull Duration duration,
			@NonNull List<@NonNull Throwable> throwables) {
	}

	private static final class Fixture {
		private final McpLocalSubscriptionEventPublisher publisher =
				McpLocalSubscriptionEventPublisher.fromDefaults();
		private final RecordingMetrics metrics;
		private final RecordingLifecycle lifecycle;
		private final AtomicInteger handlerCalls = new AtomicInteger();
		private final AtomicInteger interceptorCalls = new AtomicInteger();
		private final CountDownLatch blockingToolEntered = new CountDownLatch(1);
		private final CountDownLatch releaseBlockingTool = new CountDownLatch(1);
		private final CountDownLatch cancelObserved = new CountDownLatch(1);
		@Nullable
		private final CountDownLatch concurrentAdmissions;
		@Nullable
		private final CountDownLatch releaseConcurrentAdmissions;
		private final McpServer server;
		private final SokletConfig config;

		private Fixture(int expectedFinishes, boolean blockingTool) {
			this.metrics = new RecordingMetrics(expectedFinishes);
			this.lifecycle = new RecordingLifecycle(expectedFinishes);
			this.concurrentAdmissions = expectedFinishes == OPERATIONS.size()
					? new CountDownLatch(expectedFinishes) : null;
			this.releaseConcurrentAdmissions = this.concurrentAdmissions == null
					? null : new CountDownLatch(1);
			McpToolRegistration<McpJsonObject> tool = McpToolRegistration
					.withName(TOOL_NAME).jsonArguments()
					.handler((request, arguments, features) -> {
						this.handlerCalls.incrementAndGet();
						if (blockingTool) {
							CancelationToken token = features.require(CancelationToken.class);
							token.onCancel(this.cancelObserved::countDown);
							this.blockingToolEntered.countDown();
							awaitLatch(this.releaseBlockingTool);
						}
						return McpCompleteResult.fromToolText("matrix tool complete");
					}).build();
			McpPromptRegistration prompt = McpPromptRegistration
					.withName(PROMPT_NAME)
					.handler((request, get, features) -> {
						this.handlerCalls.incrementAndGet();
						return McpCompleteResult.fromPromptOutput(McpPromptOutput.builder()
								.description("Matrix prompt")
								.message(McpPromptMessage.fromUserContent(
										McpTextContent.fromText(
												"matrix prompt complete")))
								.build());
					}).build();
			McpResourceRegistration exact = McpResourceRegistration
					.withUriAndName(URI.create(RESOURCE_URI), "Matrix resource")
					.handler((request, read, features) -> {
						this.handlerCalls.incrementAndGet();
						return completeText(read.getUri(), "matrix resource complete");
					}).build();
			McpResourceRegistration template = McpResourceRegistration
					.withUriTemplateAndName(TEMPLATE_URI, "Matrix template")
					.handler((request, read, features) -> {
						this.handlerCalls.incrementAndGet();
						return completeText(read.getUri(), "matrix template complete");
					}).build();
			McpSubscriptionConfig subscriptions = McpSubscriptionConfig
					.withEventPublisher(this.publisher)
					.notificationTypes(EnumSet.of(
							McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED))
					.build();
			McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
					.serverInformation(McpImplementation.withNameAndVersion(
							"simulator-every-operation-test",
							"3.6.0-SNAPSHOT").build())
					.tool(tool)
					.prompt(prompt)
					.resource(exact)
					.resource(template)
					.subscriptions(subscriptions)
					.build();
			this.server = McpServer.withPort(0)
					.host(LOOPBACK)
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
					.admissionController(context -> {
						if (this.concurrentAdmissions != null) {
							this.concurrentAdmissions.countDown();
							if (this.concurrentAdmissions.getCount() == 0)
								this.releaseConcurrentAdmissions.countDown();
							Assertions.assertTrue(awaitLatch(
									this.releaseConcurrentAdmissions));
						}
						return McpAdmissionDecision.accepted();
					})
					.requestRateLimiter(context -> McpRateLimitDecision.allowed())
					.toolRateLimiter(context -> McpRateLimitDecision.allowed())
					.handlerInterceptor((context, continuation) -> {
						this.interceptorCalls.incrementAndGet();
						return continuation.proceed();
					})
					.corsAuthorizer(CorsAuthorizer.acceptAllInstance())
					.allowedHosts(Set.of(LOOPBACK))
					.requestHandlerConcurrency(16)
					.shutdownTimeout(Duration.ofMillis(250))
					.build();
			this.config = SokletConfig.withMcpServer(this.server)
					.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
					.metricsCollector(this.metrics)
					.lifecycleObservers(List.of(this.lifecycle))
					.build();
		}

		@NonNull
		private static McpCompleteResult completeText(@NonNull URI uri,
				@NonNull String text) {
			return McpCompleteResult.fromResourceOutput(McpResourceOutput.builder()
					.content(McpTextResourceContents.withUriAndText(uri, text).build())
					.build());
		}

		private void assertFinished(
				@NonNull Map<@NonNull String, @NonNull McpRequestOutcome> outcomes) {
			Assertions.assertTrue(this.metrics.awaitAllFinished());
			Assertions.assertTrue(this.lifecycle.awaitAllFinished());
			List<McpMetricsEvent> allEvents = this.metrics.events();
			Assertions.assertEquals(outcomes.size(), this.lifecycle.started().size(),
					this.lifecycle.started().toString());
			Assertions.assertEquals(outcomes.size(), this.lifecycle.finished().size(),
					this.lifecycle.finished().toString());
			Assertions.assertEquals(outcomes.size(), allEvents.stream()
					.filter(McpMetricsEvent.RequestAccepted.class::isInstance).count(),
					allEvents.toString());
			Assertions.assertTrue(allEvents.stream().noneMatch(event ->
					event instanceof McpMetricsEvent.RequestRejected
							|| event instanceof McpMetricsEvent.ProtocolError
							|| event instanceof McpMetricsEvent.UnknownMirroredHeader),
					allEvents.toString());
			for (Map.Entry<String, McpRequestOutcome> entry : outcomes.entrySet()) {
				Assertions.assertEquals(1L, allEvents.stream()
						.filter(McpMetricsEvent.RequestStarted.class::isInstance)
						.map(McpMetricsEvent.RequestStarted.class::cast)
						.filter(event -> event.getJsonRpcMethod().equals(entry.getKey()))
						.count(), allEvents.toString());
				Assertions.assertEquals(1L, allEvents.stream()
						.filter(McpMetricsEvent.RequestFinished.class::isInstance)
						.map(McpMetricsEvent.RequestFinished.class::cast)
						.filter(event -> event.getJsonRpcMethod().equals(entry.getKey())
								&& event.getOutcome() == entry.getValue())
						.count(), allEvents.toString());
				Assertions.assertTrue(this.metrics.startedBeforeFinished(entry.getKey()),
						allEvents.toString());
				List<McpRequestContext> starts = this.lifecycle.started().stream()
						.filter(context -> context.getJsonRpcMethod()
								.equals(entry.getKey()))
						.toList();
				List<FinishedObservation> finishes = this.lifecycle.finished().stream()
						.filter(finished -> finished.context().getJsonRpcMethod()
								.equals(entry.getKey()))
						.toList();
				Assertions.assertEquals(1, starts.size(), starts.toString());
				Assertions.assertEquals(1, finishes.size(), finishes.toString());
				FinishedObservation finish = finishes.get(0);
				Assertions.assertSame(starts.get(0), finish.context());
				Assertions.assertEquals(MCP_PATH,
						finish.context().getEndpoint().getPath());
				Assertions.assertEquals(entry.getValue(), finish.outcome());
				Assertions.assertNull(finish.error());
				Assertions.assertFalse(finish.duration().isNegative());
				Assertions.assertTrue(finish.throwables().isEmpty());
				Assertions.assertTrue(this.lifecycle.startedBeforeFinished(entry.getKey()));
			}
			if (outcomes.size() == 1)
				assertExactSingleRequestMetricOrder(
						outcomes.keySet().iterator().next(), allEvents);
		}

		private static void assertExactSingleRequestMetricOrder(
				@NonNull String method,
				@NonNull List<@NonNull McpMetricsEvent> events) {
			List<Class<?>> expected = switch (method) {
				case "tools/call", "prompts/get", "resources/read" -> List.of(
						McpMetricsEvent.RequestAccepted.class,
						McpMetricsEvent.RequestStarted.class,
						McpMetricsEvent.HandlerExecutionStarted.class,
						McpMetricsEvent.RequestFinished.class,
						McpMetricsEvent.HandlerExecutionFinished.class);
				case "subscriptions/listen" -> List.of(
						McpMetricsEvent.RequestAccepted.class,
						McpMetricsEvent.RequestStarted.class,
						McpMetricsEvent.RequestStreamOpened.class,
						McpMetricsEvent.SubscriptionOpened.class,
						McpMetricsEvent.RequestStreamClosed.class,
						McpMetricsEvent.SubscriptionClosed.class,
						McpMetricsEvent.RequestFinished.class);
				default -> List.of(
						McpMetricsEvent.RequestAccepted.class,
						McpMetricsEvent.RequestStarted.class,
						McpMetricsEvent.RequestFinished.class);
			};
			Assertions.assertEquals(expected,
					events.stream().map(Object::getClass).toList(), events.toString());
		}

		private void assertOffNetwork() {
			this.metrics.assertOffNetwork();
			Assertions.assertEquals(0, this.lifecycle.serverCallbacks());
			assertStoppedDiagnostics(this.server);
		}

		private McpLocalSubscriptionEventPublisher publisher() {
			return this.publisher;
		}

		private RecordingMetrics metrics() {
			return this.metrics;
		}

		private AtomicInteger handlerCalls() {
			return this.handlerCalls;
		}

		private AtomicInteger interceptorCalls() {
			return this.interceptorCalls;
		}

		private CountDownLatch blockingToolEntered() {
			return this.blockingToolEntered;
		}

		private CountDownLatch releaseBlockingTool() {
			return this.releaseBlockingTool;
		}

		private CountDownLatch cancelObserved() {
			return this.cancelObserved;
		}

		private McpServer server() {
			return this.server;
		}

		private SokletConfig config() {
			return this.config;
		}
	}

	private static final class RecordingMetrics implements MetricsCollector {
		private final List<McpMetricsEvent> events = new CopyOnWriteArrayList<>();
		private final CountDownLatch finished;
		private final CountDownLatch notificationFinished = new CountDownLatch(1);

		private RecordingMetrics(int expectedFinishes) {
			this.finished = new CountDownLatch(expectedFinishes);
		}

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			this.events.add(event);
			if (event instanceof McpMetricsEvent.RequestFinished finished) {
				this.finished.countDown();
				if (finished.getJsonRpcMethod().equals("notifications/cancelled"))
					this.notificationFinished.countDown();
			}
		}

		private boolean awaitAllFinished() {
			return awaitLatch(this.finished);
		}

		private boolean awaitFinishedMethod(@NonNull String method) {
			if (method.equals("notifications/cancelled"))
				return awaitLatch(this.notificationFinished);
			throw new IllegalArgumentException("No dedicated method fence: " + method);
		}

		private boolean startedBeforeFinished(@NonNull String method) {
			int started = -1;
			int finished = -1;
			for (int i = 0; i < this.events.size(); i++) {
				McpMetricsEvent event = this.events.get(i);
				if (event instanceof McpMetricsEvent.RequestStarted requestStarted
						&& requestStarted.getJsonRpcMethod().equals(method))
					started = i;
				if (event instanceof McpMetricsEvent.RequestFinished requestFinished
						&& requestFinished.getJsonRpcMethod().equals(method)) {
					finished = i;
					break;
				}
			}
			return started >= 0 && finished > started;
		}

		private void assertOffNetwork() {
			Assertions.assertTrue(this.events.stream().noneMatch(event ->
					event instanceof McpMetricsEvent.ServerStarted
							|| event instanceof McpMetricsEvent.ServerStopped
							|| event instanceof McpMetricsEvent.ConnectionAccepted
							|| event instanceof McpMetricsEvent.ConnectionRejected
							|| event instanceof McpMetricsEvent.TransportFailure),
					this.events.toString());
		}

		private List<McpMetricsEvent> events() {
			return List.copyOf(this.events);
		}
	}

	private static final class RecordingLifecycle implements LifecycleObserver {
		private final List<String> order = new CopyOnWriteArrayList<>();
		private final List<McpRequestContext> started =
				new CopyOnWriteArrayList<>();
		private final List<FinishedObservation> finished =
				new CopyOnWriteArrayList<>();
		private final CountDownLatch finishLatch;
		private final AtomicInteger serverCallbacks = new AtomicInteger();

		private RecordingLifecycle(int expectedFinishes) {
			this.finishLatch = new CountDownLatch(expectedFinishes);
		}

		@Override
		public void didStartMcpRequestHandling(@NonNull McpRequestContext context) {
			this.started.add(context);
			this.order.add("start:" + context.getJsonRpcMethod());
		}

		@Override
		public void didFinishMcpRequestHandling(@NonNull McpRequestContext context,
				@NonNull McpRequestOutcome outcome,
				@Nullable McpJsonRpcError error, @NonNull Duration duration,
				@NonNull List<@NonNull Throwable> throwables) {
			this.finished.add(new FinishedObservation(context, outcome, error,
					duration, List.copyOf(throwables)));
			this.order.add("finish:" + context.getJsonRpcMethod());
			this.finishLatch.countDown();
		}

		@Override
		public void willStartMcpServer(@NonNull McpServer server) {
			this.serverCallbacks.incrementAndGet();
		}

		@Override
		public void didStartMcpServer(@NonNull McpServer server) {
			this.serverCallbacks.incrementAndGet();
		}

		@Override
		public void didFailToStartMcpServer(@NonNull McpServer server,
				@NonNull Throwable throwable) {
			this.serverCallbacks.incrementAndGet();
		}

		@Override
		public void willStopMcpServer(@NonNull McpServer server) {
			this.serverCallbacks.incrementAndGet();
		}

		@Override
		public void didStopMcpServer(@NonNull McpServer server,
				@NonNull McpShutdownOutcome outcome) {
			this.serverCallbacks.incrementAndGet();
		}

		@Override
		public void didFailToStopMcpServer(@NonNull McpServer server,
				@NonNull Throwable throwable) {
			this.serverCallbacks.incrementAndGet();
		}

		private boolean awaitAllFinished() {
			return awaitLatch(this.finishLatch);
		}

		private boolean startedBeforeFinished(@NonNull String method) {
			return this.order.indexOf("start:" + method)
					< this.order.indexOf("finish:" + method)
					&& this.order.indexOf("start:" + method) >= 0;
		}

		private List<FinishedObservation> finished() {
			return List.copyOf(this.finished);
		}

		private List<McpRequestContext> started() {
			return List.copyOf(this.started);
		}

		private int serverCallbacks() {
			return this.serverCallbacks.get();
		}
	}
}
