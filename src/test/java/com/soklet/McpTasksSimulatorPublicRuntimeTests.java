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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end off-network coverage for MCP Tasks through the public simulator.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpTasksSimulatorPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String TASKS_EXTENSION_ID =
			"io.modelcontextprotocol/tasks";
	private static final String SUBSCRIPTION_ID_KEY =
			"io.modelcontextprotocol/subscriptionId";
	private static final String TOOL_NAME = "tasks.simulator";
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final McpInputRequestDeclaration ROOTS_DECLARATION =
			McpInputRequestDeclaration.fromRoots(McpInputRequirement.CONDITIONAL);
	private static final McpJsonObject EMPTY_ROOTS_RESPONSE =
			McpJsonObject.builder()
					.put("roots", McpJsonArray.emptyInstance())
					.build();

	@Test
	@Timeout(120)
	public void sequentialRequestsExerciseCreateGetInputUpdateAndCancelation()
			throws Exception {
		Fixture fixture = new Fixture();

		SokletSimulator.run(fixture.sokletConfig(), simulator -> {
			McpServer simulatedServer = simulator.getMcpServer().orElseThrow();
			Assertions.assertNotSame(fixture.sourceServer(), simulatedServer);
			Assertions.assertSame(fixture.taskManager(),
					simulatedServer.getTaskManager().orElseThrow());

			String creation = performJson(simulator, request("tools/call",
					TOOL_NAME, "create", "\"name\":\"" + TOOL_NAME
							+ "\",\"arguments\":{}", true));
			String taskId = fixture.createdTaskId();
			Assertions.assertTrue(creation.contains("\"resultType\":\"task\""),
					creation);
			Assertions.assertTrue(creation.contains("\"taskId\":\"" + taskId
					+ "\""), creation);

			String working = getTask(simulator, "get-working", taskId);
			Assertions.assertTrue(working.contains("\"status\":\"working\""),
					working);

			Map<String, McpInputRequest> inputRequests = new LinkedHashMap<>();
			inputRequests.put("first", rootsRequest());
			inputRequests.put("second", rootsRequest());
			fixture.taskManager().requestTaskInput(taskId, inputRequests,
					"Waiting for roots");

			String inputRequired = getTask(simulator, "get-input", taskId);
			Assertions.assertTrue(inputRequired.contains(
					"\"status\":\"input_required\""), inputRequired);
			Assertions.assertTrue(inputRequired.contains("\"first\":{")
					&& inputRequired.contains("\"second\":{"), inputRequired);
			Assertions.assertTrue(inputRequired.contains("\"method\":\"roots/list\""),
					inputRequired);

			assertEmptyAcknowledgement(performJson(simulator,
					request("tasks/update", taskId, "update-first",
							"\"taskId\":\"" + taskId + "\","
									+ "\"inputResponses\":{"
									+ "\"first\":{\"roots\":[]},"
									+ "\"unknown\":{\"roots\":[]}}", true)));
			McpTask partial = fixture.taskManager().findTask(taskId).orElseThrow();
			Assertions.assertEquals(McpTaskStatus.INPUT_REQUIRED,
					partial.getTaskStatus());
			Assertions.assertEquals(List.of("second"),
					List.copyOf(partial.getInputRequests().keySet()));
			McpInputResponses firstResponses = fixture.taskManager()
					.takeTaskInputResponses(taskId);
			Assertions.assertEquals(EMPTY_ROOTS_RESPONSE,
					firstResponses.find("first").orElseThrow());
			Assertions.assertTrue(firstResponses.find("unknown").isEmpty());

			String afterPartial = getTask(simulator, "get-partial", taskId);
			Assertions.assertFalse(afterPartial.contains("\"first\":{"),
					afterPartial);
			Assertions.assertTrue(afterPartial.contains("\"second\":{"),
					afterPartial);

			assertEmptyAcknowledgement(performJson(simulator,
					request("tasks/update", taskId, "update-second",
							"\"taskId\":\"" + taskId + "\","
									+ "\"inputResponses\":{"
									+ "\"second\":{\"roots\":[]}}", true)));
			Assertions.assertEquals(McpTaskStatus.WORKING,
					fixture.taskManager().findTask(taskId).orElseThrow()
							.getTaskStatus());
			Assertions.assertEquals(EMPTY_ROOTS_RESPONSE,
					fixture.taskManager().takeTaskInputResponses(taskId)
							.find("second").orElseThrow());

			assertEmptyAcknowledgement(performJson(simulator,
					request("tasks/cancel", taskId, "cancel",
							"\"taskId\":\"" + taskId + "\"", false)));
			Assertions.assertTrue(fixture.taskManager()
					.isTaskCancelationRequested(taskId));
			fixture.taskManager().cancelTask(taskId, "Canceled by simulator test");
			String canceled = getTask(simulator, "get-canceled", taskId);
			Assertions.assertTrue(canceled.contains("\"status\":\"cancelled\""),
					canceled);
			Assertions.assertTrue(canceled.contains(
					"\"statusMessage\":\"Canceled by simulator test\""), canceled);
		});

		Assertions.assertEquals(McpServerStatus.NOT_STARTED,
				fixture.sourceServer().getDiagnostics().getStatus());
	}

	@Test
	@Timeout(120)
	public void admittedTaskRequestsPublishExactLifecycleAndMetricsOffNetwork()
			throws Exception {
		Fixture fixture = new Fixture();
		McpTaskRequestObservabilityRecorder recorder =
				new McpTaskRequestObservabilityRecorder(7);
		SimulatorConfig config = SimulatorConfig
				.withSokletConfig(fixture.sokletConfig())
				.lifecycleObservers(List.of(recorder.lifecycleObserver()))
				.metricsCollector(recorder.metricsCollector())
				.build();
		AtomicReference<String> taskId = new AtomicReference<>();
		String unknownTaskId = "simulator-observed-unknown-task";

		SokletSimulator.run(config, simulator -> {
			String creation = performJson(simulator, request("tools/call",
					TOOL_NAME, "simulator-observed-create", "\"name\":\""
							+ TOOL_NAME + "\",\"arguments\":{}", true));
			taskId.set(fixture.createdTaskId());
			Assertions.assertTrue(creation.contains("\"resultType\":\"task\""),
					creation);
			performJson(simulator, request("tasks/get", taskId.get(),
					"simulator-observed-get", "\"taskId\":\"" + taskId.get()
							+ "\"", true));
			performJson(simulator, request("tasks/update", taskId.get(),
					"simulator-observed-update", "\"taskId\":\"" + taskId.get()
							+ "\",\"inputResponses\":{}", true));
			performJson(simulator, request("tasks/cancel", taskId.get(),
					"simulator-observed-cancel", "\"taskId\":\"" + taskId.get()
							+ "\"", true));

			assertInvalidParams(performJson(simulator, request("tasks/get",
					unknownTaskId, "simulator-observed-get-missing",
					"\"taskId\":\"" + unknownTaskId + "\"", true), 400),
					"simulator-observed-get-missing");
			assertInvalidParams(performJson(simulator, request("tasks/update",
					unknownTaskId, "simulator-observed-update-missing",
					"\"taskId\":\"" + unknownTaskId
							+ "\",\"inputResponses\":{}", true), 400),
					"simulator-observed-update-missing");
			assertInvalidParams(performJson(simulator, request("tasks/cancel",
					unknownTaskId, "simulator-observed-cancel-missing",
					"\"taskId\":\"" + unknownTaskId + "\"", true), 400),
					"simulator-observed-cancel-missing");
			Assertions.assertEquals(7, fixture.admissions().get());
		});

		recorder.awaitAndAssert(MCP_PATH, List.of(
				McpTaskRequestObservabilityRecorder.complete(
						"simulator-observed-create", "tools/call", TOOL_NAME,
						McpOperationType.TOOLS_CALL),
				McpTaskRequestObservabilityRecorder.complete(
						"simulator-observed-get", "tasks/get", taskId.get(),
						McpOperationType.TASKS_GET),
				McpTaskRequestObservabilityRecorder.complete(
						"simulator-observed-update", "tasks/update", taskId.get(),
						McpOperationType.TASKS_UPDATE),
				McpTaskRequestObservabilityRecorder.complete(
						"simulator-observed-cancel", "tasks/cancel", taskId.get(),
						McpOperationType.TASKS_CANCEL),
				McpTaskRequestObservabilityRecorder.protocolError(
						"simulator-observed-get-missing", "tasks/get",
						unknownTaskId, McpOperationType.TASKS_GET),
				McpTaskRequestObservabilityRecorder.protocolError(
						"simulator-observed-update-missing", "tasks/update",
						unknownTaskId, McpOperationType.TASKS_UPDATE),
				McpTaskRequestObservabilityRecorder.protocolError(
						"simulator-observed-cancel-missing", "tasks/cancel",
						unknownTaskId, McpOperationType.TASKS_CANCEL)));
		Assertions.assertEquals(McpServerStatus.NOT_STARTED,
				fixture.sourceServer().getDiagnostics().getStatus());
	}

	@Test
	@Timeout(120)
	public void taskSubscriptionUsesCurrentStateAndReconnectDoesNotReplay()
			throws Exception {
		Fixture fixture = new Fixture();

		SokletSimulator.run(fixture.sokletConfig(), simulator -> {
			performJson(simulator, request("tools/call", TOOL_NAME,
					"create-for-subscription", "\"name\":\"" + TOOL_NAME
							+ "\",\"arguments\":{}", false));
			String taskId = fixture.createdTaskId();

			McpSimulation first = openTaskSubscription(simulator,
					"first-subscription", taskId);
			assertTaskAcknowledgement(nextJsonItem(first),
					"first-subscription", taskId);
			Assertions.assertTrue(first.awaitStreamItem(Duration.ZERO).isEmpty());
			first.close();
			Assertions.assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED,
					awaitCompletion(first).getReason());
			awaitActiveSubscriptions(simulator.getMcpServer().orElseThrow(), 0);

			McpTask completed = fixture.taskManager().completeTask(taskId,
					McpCompleteResult.fromToolText("finished off-network"),
					"Complete");

			McpSimulation second = openTaskSubscription(simulator,
					"second-subscription", taskId);
			assertTaskAcknowledgement(nextJsonItem(second),
					"second-subscription", taskId);
			Assertions.assertTrue(second.awaitStreamItem(
					Duration.ofMillis(100)).isEmpty(),
					"An event published while disconnected must not replay.");

			fixture.taskManager().getTaskEventPublisher().orElseThrow()
					.publishTaskChanged(taskId);
			McpJsonObject notification = nextJsonItem(second);
			assertCompletedTaskNotification(notification, "second-subscription",
					completed);

			String polled = getTask(simulator, "poll-completed", taskId);
			Assertions.assertTrue(polled.contains("\"taskId\":\"" + taskId
					+ "\""), polled);
			Assertions.assertTrue(polled.contains("\"status\":\"completed\""),
					polled);
			Assertions.assertTrue(polled.contains("\"lastUpdatedAt\":\""
					+ completed.getLastUpdatedAt() + "\""), polled);
			Assertions.assertTrue(polled.contains("\"text\":\"finished off-network\""),
					polled);

			second.close();
			Assertions.assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED,
					awaitCompletion(second).getReason());
		});
	}

	@NonNull
	private static McpInputRequest rootsRequest() {
		return McpInputRequest.fromDeclaration(ROOTS_DECLARATION,
				McpJsonObject.emptyInstance());
	}

	@NonNull
	private static String getTask(@NonNull Simulator simulator,
			@NonNull String requestId, @NonNull String taskId)
			throws InterruptedException {
		return performJson(simulator, request("tasks/get", taskId, requestId,
				"\"taskId\":\"" + taskId + "\"", true));
	}

	@NonNull
	private static String performJson(@NonNull Simulator simulator,
			@NonNull Request request) throws InterruptedException {
		return performJson(simulator, request, 200);
	}

	@NonNull
	private static String performJson(@NonNull Simulator simulator,
			@NonNull Request request, int expectedStatus)
			throws InterruptedException {
		try (McpSimulation simulation = simulator.startMcpRequest(request)) {
			McpSimulationResponse response = simulation.awaitResponse(WAIT)
					.orElseThrow(() -> new AssertionError(
							"Timed out waiting for an MCP response."));
			Assertions.assertEquals(expectedStatus, response.getStatusCode());
			Assertions.assertEquals(McpSimulationBodyType.JSON,
					response.getBodyType());
			Assertions.assertEquals(Set.of("no-store"),
					response.getHeaders().get("Cache-Control"));
			Assertions.assertEquals(Set.of(JSON_MEDIA_TYPE),
					response.getHeaders().get("Content-Type"));
			String json = new String(response.getBody().orElseThrow(),
					StandardCharsets.UTF_8);
			McpSimulationCompletion completion = awaitCompletion(simulation);
			Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
					completion.getReason());
			Assertions.assertTrue(completion.getTerminalMessage().isEmpty());
			Assertions.assertTrue(completion.getThrowables().isEmpty());
			Assertions.assertTrue(simulation.awaitStreamItem(Duration.ZERO).isEmpty());
			return json;
		}
	}

	private static void assertEmptyAcknowledgement(@NonNull String json) {
		Assertions.assertTrue(json.contains(
				"\"result\":{\"resultType\":\"complete\"}"), json);
	}

	private static void assertInvalidParams(@NonNull String json,
			@NonNull String requestId) {
		Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"error\":{\"code\":-32602,\"message\":"
				+ "\"Invalid params\"}}", json);
	}

	@NonNull
	private static McpSimulation openTaskSubscription(
			@NonNull Simulator simulator, @NonNull String subscriptionId,
			@NonNull String taskId) throws InterruptedException {
		McpSimulation simulation = simulator.startMcpRequest(request(
				"subscriptions/listen", null, subscriptionId,
				"\"notifications\":{\"taskIds\":[\"" + taskId + "\"]}",
				false));
		McpSimulationResponse response = simulation.awaitResponse(WAIT)
				.orElseThrow(() -> new AssertionError(
						"Timed out waiting for an MCP subscription response."));
		Assertions.assertEquals(200, response.getStatusCode());
		Assertions.assertEquals(McpSimulationBodyType.SSE,
				response.getBodyType());
		Assertions.assertTrue(response.getBody().isEmpty());
		Assertions.assertEquals(Set.of("text/event-stream"),
				response.getHeaders().get("Content-Type"));
		return simulation;
	}

	@NonNull
	private static McpJsonObject nextJsonItem(@NonNull McpSimulation simulation)
			throws InterruptedException {
		McpSimulationStreamItem item = simulation.awaitStreamItem(WAIT)
				.orElseThrow(() -> new AssertionError(
						"Timed out waiting for an MCP stream item."));
		Assertions.assertEquals(McpSimulationStreamItemType.JSON_MESSAGE,
				item.getType());
		Assertions.assertTrue(item.getComment().isEmpty());
		return Assertions.assertInstanceOf(McpJsonObject.class,
				item.getMessage().orElseThrow());
	}

	private static void assertTaskAcknowledgement(
			@NonNull McpJsonObject message, @NonNull String subscriptionId,
			@NonNull String taskId) {
		Assertions.assertEquals("notifications/subscriptions/acknowledged",
				stringMember(message, "method"));
		McpJsonObject params = objectMember(message, "params");
		Assertions.assertEquals(subscriptionId,
				stringMember(objectMember(params, "_meta"), SUBSCRIPTION_ID_KEY));
		McpJsonArray taskIds = Assertions.assertInstanceOf(McpJsonArray.class,
				objectMember(params, "notifications").find("taskIds")
						.orElseThrow());
		Assertions.assertEquals(List.of(McpJsonString.fromValue(taskId)),
				taskIds.getElements());
	}

	private static void assertCompletedTaskNotification(
			@NonNull McpJsonObject message, @NonNull String subscriptionId,
			@NonNull McpTask task) {
		Assertions.assertEquals("notifications/tasks",
				stringMember(message, "method"));
		McpJsonObject params = objectMember(message, "params");
		Assertions.assertEquals(task.getTaskId(),
				stringMember(params, "taskId"));
		Assertions.assertEquals("completed", stringMember(params, "status"));
		Assertions.assertEquals(task.getCreatedAt().toString(),
				stringMember(params, "createdAt"));
		Assertions.assertEquals(task.getLastUpdatedAt().toString(),
				stringMember(params, "lastUpdatedAt"));
		Assertions.assertTrue(params.find("resultType").isEmpty(),
				"A task notification is a task snapshot, not a result envelope.");
		McpJsonObject metadata = objectMember(params, "_meta");
		Assertions.assertEquals(subscriptionId,
				stringMember(metadata, SUBSCRIPTION_ID_KEY));
		McpJsonObject result = objectMember(params, "result");
		Assertions.assertEquals("complete", stringMember(result, "resultType"));
		McpJsonArray content = Assertions.assertInstanceOf(McpJsonArray.class,
				result.find("content").orElseThrow());
		McpJsonObject text = Assertions.assertInstanceOf(McpJsonObject.class,
				content.getElements().get(0));
		Assertions.assertEquals("finished off-network",
				stringMember(text, "text"));
	}

	@NonNull
	private static McpJsonObject objectMember(@NonNull McpJsonObject object,
			@NonNull String name) {
		return Assertions.assertInstanceOf(McpJsonObject.class,
				object.find(name).orElseThrow());
	}

	@NonNull
	private static String stringMember(@NonNull McpJsonObject object,
			@NonNull String name) {
		return Assertions.assertInstanceOf(McpJsonString.class,
				object.find(name).orElseThrow()).getValue();
	}

	@NonNull
	private static McpSimulationCompletion awaitCompletion(
			@NonNull McpSimulation simulation) throws InterruptedException {
		return simulation.awaitCompletion(WAIT)
				.orElseThrow(() -> new AssertionError(
						"Timed out waiting for MCP simulation completion."));
	}

	private static void awaitActiveSubscriptions(@NonNull McpServer server,
			int expected) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (server.getDiagnostics().getActiveSubscriptions() != expected) {
			if (System.nanoTime() - deadline >= 0L)
				throw new AssertionError("Timed out waiting for " + expected
						+ " active MCP subscriptions.");
			Thread.sleep(10L);
		}
	}

	@NonNull
	private static Request request(@NonNull String method,
			@Nullable String operationName, @NonNull String requestId,
			@NonNull String fields, boolean rootsCapable) {
		String capabilities = "{\"extensions\":{\"" + TASKS_EXTENSION_ID
				+ "\":{}}" + (rootsCapable ? ",\"roots\":{}" : "") + "}";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"" + method + "\",\"params\":{" + fields
				+ ",\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":"
				+ capabilities + "}}}";
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("Host", Set.of(LOOPBACK + ":0"));
		headers.put("Content-Type", Set.of(JSON_MEDIA_TYPE + "; charset=UTF-8"));
		headers.put("Accept", Set.of(JSON_MEDIA_TYPE + ", text/event-stream"));
		headers.put("MCP-Protocol-Version", Set.of(PROTOCOL_VERSION));
		headers.put("Mcp-Method", Set.of(method));
		if (operationName != null)
			headers.put("Mcp-Name", Set.of(operationName));
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	private static final class Fixture {
		@NonNull
		private final McpInMemoryTaskManager taskManager;
		@NonNull
		private final AtomicInteger admissions;
		@NonNull
		private final AtomicReference<@Nullable String> createdTaskId;
		@NonNull
		private final McpServer sourceServer;
		@NonNull
		private final SokletConfig sokletConfig;

		private Fixture() {
			this.taskManager = McpTaskManager.fromInMemoryDefaults();
			this.admissions = new AtomicInteger();
			this.createdTaskId = new AtomicReference<>();
			McpToolRegistration<McpJsonObject> tool = McpToolRegistration
					.withName(TOOL_NAME)
					.jsonObjectArguments()
					.handler((request, arguments, features) -> {
						McpTask task = this.taskManager.createTask(
								features.getTaskControl().orElseThrow());
						this.createdTaskId.set(task.getTaskId());
						return McpTaskCreatedResult
								.<McpJsonObject>fromTaskId(task.getTaskId());
					})
					.addInputRequestDeclaration(ROOTS_DECLARATION)
					.structuredContentMirroredAsText(false)
					.build();
			McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
					McpImplementation.withNameAndVersion(
							"tasks-simulator-test", "4.0.0").build())
					.serverInformationIncluded(false)
					.addTool(tool)
					.build();
			this.sourceServer = McpServer.withPort(0)
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(
							List.of(endpoint)))
					.admissionController(context -> {
						this.admissions.incrementAndGet();
						return McpAdmissionDecision.accepted(McpAdmissionIdentity
								.withRateLimitPartitionKey("simulator-rate")
								.authorizationPartitionKey("simulator-owner")
								.build());
					})
					.host(LOOPBACK)
					.requestRateLimiter(context -> McpRateLimitDecision.allowed())
					.toolRateLimiter(context -> McpRateLimitDecision.allowed())
					.taskManager(this.taskManager)
					.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
					.allowedHosts(Set.of(LOOPBACK))
					.build();
			this.sokletConfig = SokletConfig.withMcpServer(this.sourceServer)
					.resourceMethodResolver(
							ResourceMethodResolver.fromMethods(Set.of()))
					.build();
		}

		@NonNull
		private McpInMemoryTaskManager taskManager() {
			return this.taskManager;
		}

		@NonNull
		private AtomicInteger admissions() {
			return this.admissions;
		}

		@NonNull
		private String createdTaskId() {
			return Optional.ofNullable(this.createdTaskId.get())
					.orElseThrow(() -> new AssertionError(
							"The simulated tool did not create a task."));
		}

		@NonNull
		private McpServer sourceServer() {
			return this.sourceServer;
		}

		@NonNull
		private SokletConfig sokletConfig() {
			return this.sokletConfig;
		}
	}
}
