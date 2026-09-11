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

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real-listener evidence for durable MCP Tasks behavior in a rolling fleet.
 *
 * <p>The fixture deliberately models only application-owned durable storage;
 * it is not a worker runtime. Two independent Soklet nodes share that storage,
 * while every protocol operation is independently admitted and authorized.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@Timeout(60)
class McpTasksFleetPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MAIN_PATH = "/tasks/fleet";
	private static final String OTHER_PATH = "/tasks/other";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String TASKS_EXTENSION_ID =
			"io.modelcontextprotocol/tasks";
	private static final String TOOL_NAME = "tasks.fleet";
	private static final String TENANT_ALPHA = "tenant-alpha";
	private static final String TENANT_BETA = "tenant-beta";
	private static final McpInputRequestDeclaration ROOTS_DECLARATION =
			McpInputRequestDeclaration.fromRoots(
					McpInputRequirement.CONDITIONAL);
	private static final Instant CREATED_AT =
			Instant.parse("2026-09-09T12:00:00Z");
	private static final Instant LAST_UPDATED_AT =
			Instant.parse("2026-09-09T12:00:01Z");

	@Test
	@Timeout(180)
	void completedTasksMoveAcrossNodesAndSurviveOriginNodeReplacement()
			throws Exception {
		DurableFleetTaskManager taskManager = new DurableFleetTaskManager();
		McpServer firstServer = server("node-a", taskManager,
				NodeAOutput.class, "nodeAValue");
		McpServer secondServer = server("node-b", taskManager,
				NodeBOutput.class, "nodeBValue");
		Soklet first = managedSoklet(firstServer);
		Soklet second = managedSoklet(secondServer);
		Soklet replacement = null;

		try {
			first.start();
			second.start();
			int firstPort = boundPort(firstServer);
			int secondPort = boundPort(secondServer);

			HttpResponse<String> firstCreation = callTool(firstPort,
					"create-on-a", "task-from-a", TENANT_ALPHA);
			assertTaskHandle(firstCreation, "task-from-a");
			HttpResponse<String> firstRecoveredOnSecond = taskRequest(
					secondPort, MAIN_PATH, "tasks/get", "recover-a-on-b",
					"task-from-a", TENANT_ALPHA);
			assertTaskResult(firstRecoveredOnSecond, "nodeAValue", "node-a");

			HttpResponse<String> secondCreation = callTool(secondPort,
					"create-on-b", "task-from-b", TENANT_ALPHA);
			assertTaskHandle(secondCreation, "task-from-b");
			HttpResponse<String> secondRecoveredOnFirst = taskRequest(
					firstPort, MAIN_PATH, "tasks/get", "recover-b-on-a",
					"task-from-b", TENANT_ALPHA);
			assertTaskResult(secondRecoveredOnFirst, "nodeBValue", "node-b");

			first.close();
			Assertions.assertEquals(2, taskManager.taskCount());
			assertTaskResult(taskRequest(secondPort, MAIN_PATH, "tasks/get",
					"after-node-loss", "task-from-a", TENANT_ALPHA),
					"nodeAValue", "node-a");

			McpServer replacementServer = serverWithoutTool("node-a-replacement",
					taskManager);
			replacement = managedSoklet(replacementServer);
			replacement.start();
			assertTaskResult(taskRequest(boundPort(replacementServer), MAIN_PATH,
					"tasks/get", "after-node-replacement", "task-from-a",
					TENANT_ALPHA), "nodeAValue", "node-a");
			Assertions.assertEquals(0, taskManager.updateInvocations.get());
			Assertions.assertEquals(0, taskManager.cancelInvocations.get());
		} finally {
			if (replacement != null)
				replacement.close();
			second.close();
			first.close();
		}
	}

	@Test
	@Timeout(120)
	void taskOperationsPreserveEndpointAndTenantIsolationAcrossNodes()
			throws Exception {
		DurableFleetTaskManager taskManager = new DurableFleetTaskManager();
		McpServer firstServer = server("node-a", taskManager,
				NodeAOutput.class, "nodeAValue");
		McpServer secondServer = server("node-b", taskManager,
				NodeBOutput.class, "nodeBValue");
		Soklet first = managedSoklet(firstServer);
		Soklet second = managedSoklet(secondServer);

		try {
			first.start();
			second.start();
			assertTaskHandle(callTool(boundPort(firstServer), "create-private",
					"private-task", TENANT_ALPHA), "private-task");
			int secondPort = boundPort(secondServer);

			for (String method : List.of("tasks/get", "tasks/update",
					"tasks/cancel")) {
				HttpResponse<String> wrongTenant = taskRequest(secondPort,
						MAIN_PATH, method, "isolated", "private-task",
						TENANT_BETA);
				HttpResponse<String> wrongEndpoint = taskRequest(secondPort,
						OTHER_PATH, method, "isolated", "private-task",
						TENANT_ALPHA);
				HttpResponse<String> unknown = taskRequest(secondPort, MAIN_PATH,
						method, "isolated", "unknown-task", TENANT_ALPHA);
				assertUnknownWithoutDisclosure(wrongTenant, "private-task");
				assertUnknownWithoutDisclosure(wrongEndpoint, "private-task");
				assertUnknownWithoutDisclosure(unknown, "unknown-task");
				Assertions.assertEquals(unknown.body(), wrongTenant.body());
				Assertions.assertEquals(unknown.body(), wrongEndpoint.body());
			}

			Assertions.assertEquals(0, taskManager.updateInvocations.get());
			Assertions.assertEquals(0, taskManager.cancelInvocations.get());
			assertTaskResult(taskRequest(secondPort, MAIN_PATH, "tasks/get",
					"authorized-get", "private-task", TENANT_ALPHA),
					"nodeAValue", "node-a");
			assertEmptyAcknowledgment(taskRequest(secondPort, MAIN_PATH,
					"tasks/update", "authorized-update", "private-task",
					TENANT_ALPHA));
			assertEmptyAcknowledgment(taskRequest(secondPort, MAIN_PATH,
					"tasks/cancel", "authorized-cancel", "private-task",
					TENANT_ALPHA));
			Assertions.assertEquals(1, taskManager.updateInvocations.get());
			Assertions.assertEquals(1, taskManager.cancelInvocations.get());
		} finally {
			second.close();
			first.close();
		}
	}

	@Test
	@Timeout(120)
	void durableSnapshotsControlTtlAndOriginVersionCompatibility()
			throws Exception {
		DurableFleetTaskManager taskManager = new DurableFleetTaskManager();
		McpServer firstServer = server("node-a", taskManager,
				NodeAOutput.class, "nodeAValue");
		McpServer secondServer = server("node-b", taskManager,
				NodeBOutput.class, "nodeBValue");
		Soklet first = managedSoklet(firstServer);
		Soklet second = managedSoklet(secondServer);

		try {
			first.start();
			second.start();
			assertTaskHandle(callTool(boundPort(firstServer), "create-versioned",
					"versioned-task", TENANT_ALPHA), "versioned-task");
			int secondPort = boundPort(secondServer);

			HttpResponse<String> original = taskRequest(secondPort, MAIN_PATH,
					"tasks/get", "ttl-original", "versioned-task",
					TENANT_ALPHA);
			assertTaskResult(original, "nodeAValue", "node-a");
			Assertions.assertTrue(original.body().contains("\"ttlMs\":30000"),
					original.body());

			McpTask source = taskManager.requireTask("versioned-task");
			taskManager.replaceTask(completedTask(source.getTaskId(),
					source.getTaskOrigin(), "nodeAValue", "node-a",
					Duration.ofMinutes(5), LAST_UPDATED_AT.plusSeconds(1)));
			HttpResponse<String> extended = taskRequest(secondPort, MAIN_PATH,
					"tasks/get", "ttl-extended", "versioned-task",
					TENANT_ALPHA);
			Assertions.assertTrue(extended.body().contains("\"ttlMs\":300000"),
					extended.body());

			taskManager.replaceTask(completedTask(source.getTaskId(),
					source.getTaskOrigin(), "nodeAValue", "node-a", null,
					LAST_UPDATED_AT.plusSeconds(2)));
			HttpResponse<String> unlimited = taskRequest(secondPort, MAIN_PATH,
					"tasks/get", "ttl-unlimited", "versioned-task",
					TENANT_ALPHA);
			Assertions.assertTrue(unlimited.body().contains("\"ttlMs\":null"),
					unlimited.body());

			McpTaskOrigin additiveOrigin = taskOriginWith(source.getTaskOrigin(),
					"futureOriginMember", McpJsonObject.builder()
							.put("revision", "future-additive").build());
			taskManager.putTaskLike("versioned-task", completedTask(
					"additive-origin-task", additiveOrigin, "nodeAValue",
					"additive", Duration.ofSeconds(30), LAST_UPDATED_AT));
			assertTaskResult(taskRequest(secondPort, MAIN_PATH, "tasks/get",
					"additive-origin", "additive-origin-task", TENANT_ALPHA),
					"nodeAValue", "additive");

			McpTaskOrigin unsupportedOrigin = taskOriginWith(
					source.getTaskOrigin(), "formatVersion",
					McpJsonNumber.fromValue(java.math.BigDecimal.valueOf(2)));
			taskManager.putTaskLike("versioned-task", completedTask(
					"unsupported-origin-task", unsupportedOrigin, "nodeAValue",
					"unsupported-origin-canary", Duration.ofSeconds(30),
					LAST_UPDATED_AT));
			HttpResponse<String> unsupported = taskRequest(secondPort, MAIN_PATH,
					"tasks/get", "unsupported-origin", "unsupported-origin-task",
					TENANT_ALPHA);
			assertNoStore(unsupported, 500);
			Assertions.assertTrue(unsupported.body().contains("\"code\":-32603"),
					unsupported.body());
			Assertions.assertFalse(unsupported.body().contains(
					"unsupported-origin-task"), unsupported.body());
			Assertions.assertFalse(unsupported.body().contains(
					"unsupported-origin-canary"), unsupported.body());
		} finally {
			second.close();
			first.close();
		}
	}

	@Test
	@Timeout(180)
	void persistedInputContractSurvivesCurrentRegistrationChanges()
			throws Exception {
		DurableFleetTaskManager taskManager = new DurableFleetTaskManager();
		McpServer rootsServer = server("node-roots", taskManager,
				NodeAOutput.class, "nodeAValue", List.of(ROOTS_DECLARATION));
		McpServer emptyServer = server("node-empty", taskManager,
				NodeBOutput.class, "nodeBValue");
		McpServer removedServer = serverWithoutTool("node-removed", taskManager);
		Soklet roots = managedSoklet(rootsServer);
		Soklet empty = managedSoklet(emptyServer);
		Soklet removed = managedSoklet(removedServer);

		try {
			roots.start();
			empty.start();
			removed.start();

			assertTaskHandle(callTool(boundPort(rootsServer), "roots-origin",
					"roots-origin-source", TENANT_ALPHA), "roots-origin-source");
			McpTask rootsSource = taskManager.requireTask("roots-origin-source");
			taskManager.putTaskLike(rootsSource.getTaskId(), inputRequiredTask(
					"persisted-roots-task", rootsSource.getTaskOrigin(),
					ROOTS_DECLARATION, "persisted-roots-canary"));

			assertRootsInputRequired(taskRequest(boundPort(emptyServer), MAIN_PATH,
					"tasks/get", "narrower-current-registration",
					"persisted-roots-task", TENANT_ALPHA, true));
			assertRootsInputRequired(taskRequest(boundPort(removedServer), MAIN_PATH,
					"tasks/get", "removed-current-registration",
					"persisted-roots-task", TENANT_ALPHA, true));

			assertTaskHandle(callTool(boundPort(emptyServer), "empty-origin",
					"empty-origin-source", TENANT_ALPHA), "empty-origin-source");
			McpTask emptySource = taskManager.requireTask("empty-origin-source");
			taskManager.putTaskLike(emptySource.getTaskId(), inputRequiredTask(
					"current-registration-must-not-broaden",
					emptySource.getTaskOrigin(), ROOTS_DECLARATION,
					"broadened-contract-canary"));
			assertInvalidOriginWithoutDisclosure(taskRequest(boundPort(rootsServer),
					MAIN_PATH, "tasks/get", "broader-current-registration",
					"current-registration-must-not-broaden", TENANT_ALPHA, true),
					"current-registration-must-not-broaden",
					"broadened-contract-canary");

			McpTaskOrigin missingDeclarations = taskOriginWithout(
					rootsSource.getTaskOrigin(), "inputRequestDeclarations");
			taskManager.putTaskLike(rootsSource.getTaskId(), inputRequiredTask(
					"missing-declarations-task", missingDeclarations,
					ROOTS_DECLARATION, "missing-declarations-canary"));
			assertInvalidOriginWithoutDisclosure(taskRequest(boundPort(rootsServer),
					MAIN_PATH, "tasks/get", "missing-declarations",
					"missing-declarations-task", TENANT_ALPHA, true),
					"missing-declarations-task", "missing-declarations-canary");

			McpTaskOrigin malformedDeclarations = taskOriginWith(
					rootsSource.getTaskOrigin(), "inputRequestDeclarations",
					McpJsonObject.emptyInstance());
			taskManager.putTaskLike(rootsSource.getTaskId(), inputRequiredTask(
					"malformed-declarations-task", malformedDeclarations,
					ROOTS_DECLARATION, "malformed-declarations-canary"));
			assertInvalidOriginWithoutDisclosure(taskRequest(boundPort(rootsServer),
					MAIN_PATH, "tasks/get", "malformed-declarations",
					"malformed-declarations-task", TENANT_ALPHA, true),
					"malformed-declarations-task", "malformed-declarations-canary");
		} finally {
			removed.close();
			empty.close();
			roots.close();
		}
	}

	@NonNull
	private static <O> McpServer server(@NonNull String node,
			@NonNull DurableFleetTaskManager taskManager,
			@NonNull Class<O> outputType, @NonNull String outputMember) {
		return server(node, taskManager, outputType, outputMember, List.of());
	}

	@NonNull
	private static <O> McpServer server(@NonNull String node,
			@NonNull DurableFleetTaskManager taskManager,
			@NonNull Class<O> outputType, @NonNull String outputMember,
			@NonNull List<@NonNull McpInputRequestDeclaration> declarations) {
		McpToolRegistration.OperationBuilder<FleetArguments> toolBuilder =
				McpToolRegistration
				.withName(TOOL_NAME)
				.argumentAndOutputTypes(FleetArguments.class, outputType)
				.operationHandler((request, arguments, features) -> {
					String taskId = arguments.getConvertedArguments().taskId();
					taskManager.createCompletedTask(features.getTaskControl()
							.orElseThrow(), taskId, outputMember, node);
					return McpTaskCreatedResult.<O>fromTaskId(taskId);
				})
				.structuredContentMirroredAsText(false);
		for (McpInputRequestDeclaration declaration : declarations)
			toolBuilder.addInputRequestDeclaration(declaration);
		McpToolRegistration<FleetArguments> tool = toolBuilder.build();
		McpEndpoint mainEndpoint = McpEndpoint.withPath(MAIN_PATH,
				McpImplementation.withNameAndVersion(node, "4.0.0").build())
				.serverInfoIncluded(false)
				.addTool(tool)
				.build();
		return server(node, taskManager, mainEndpoint);
	}

	@NonNull
	private static McpServer serverWithoutTool(@NonNull String node,
			@NonNull DurableFleetTaskManager taskManager) {
		McpEndpoint mainEndpoint = McpEndpoint.withPath(MAIN_PATH,
				McpImplementation.withNameAndVersion(node, "4.0.0").build())
				.serverInfoIncluded(false)
				.build();
		return server(node, taskManager, mainEndpoint);
	}

	@NonNull
	private static McpServer server(@NonNull String node,
			@NonNull DurableFleetTaskManager taskManager,
			@NonNull McpEndpoint mainEndpoint) {
		McpEndpoint otherEndpoint = McpEndpoint.withPath(OTHER_PATH,
				McpImplementation.withNameAndVersion(node + "-other", "4.0.0")
						.build())
				.serverInfoIncluded(false)
				.build();
		return McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(
						List.of(mainEndpoint, otherEndpoint)))
				.admissionController(context -> {
					String tenant = context.getRequest().getHeader("X-Test-Tenant")
							.orElseThrow();
					return McpAdmissionDecision.accepted(McpAdmissionIdentity
							.withRateLimitPartitionKey("rate-" + tenant)
							.authorizationPartitionKey(tenant)
							.principal(tenant)
							.build());
				})
				.host(LOOPBACK)
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.taskManager(taskManager)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
	}

	@NonNull
	private static Soklet managedSoklet(@NonNull McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.build());
	}

	private static int boundPort(@NonNull McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	@NonNull
	private static HttpResponse<String> callTool(int port,
			@NonNull String requestId, @NonNull String taskId,
			@NonNull String tenant) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"tools/call\",\"params\":{\"name\":\""
				+ TOOL_NAME + "\",\"arguments\":{\"taskId\":\"" + taskId
				+ "\"}," + taskMetadata() + "}}";
		return post(port, MAIN_PATH, "tools/call", TOOL_NAME, tenant, body);
	}

	@NonNull
	private static HttpResponse<String> taskRequest(int port,
			@NonNull String path, @NonNull String method,
			@NonNull String requestId, @NonNull String taskId,
			@NonNull String tenant) throws Exception {
		return taskRequest(port, path, method, requestId, taskId, tenant, false);
	}

	@NonNull
	private static HttpResponse<String> taskRequest(int port,
			@NonNull String path, @NonNull String method,
			@NonNull String requestId, @NonNull String taskId,
			@NonNull String tenant, boolean rootsCapable) throws Exception {
		String update = "tasks/update".equals(method)
				? ",\"inputResponses\":{\"answer\":{\"action\":\"decline\"}}"
				: "";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"" + method + "\",\"params\":{\"taskId\":\""
				+ taskId + "\"" + update + "," + taskMetadata(rootsCapable) + "}}";
		return post(port, path, method, taskId, tenant, body);
	}

	@NonNull
	private static String taskMetadata() {
		return taskMetadata(false);
	}

	@NonNull
	private static String taskMetadata(boolean rootsCapable) {
		return "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\",\"io.modelcontextprotocol/"
				+ "clientCapabilities\":{\"extensions\":{\""
				+ TASKS_EXTENSION_ID + "\":{}}"
				+ (rootsCapable ? ",\"roots\":{}" : "") + "}}";
	}

	@NonNull
	private static HttpResponse<String> post(int port, @NonNull String path,
			@NonNull String method, @NonNull String operationName,
			@NonNull String tenant, @NonNull String body) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(
				"http://" + LOOPBACK + ":" + port + path))
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method)
				.header("Mcp-Name", operationName)
				.header("X-Test-Tenant", tenant)
				.POST(HttpRequest.BodyPublishers.ofString(body,
						StandardCharsets.UTF_8))
				.build();
		return HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(5))
				.build()
				.send(request, HttpResponse.BodyHandlers.ofString(
						StandardCharsets.UTF_8));
	}

	private static void assertTaskHandle(
			@NonNull HttpResponse<String> response, @NonNull String taskId) {
		assertNoStore(response, 200);
		Assertions.assertTrue(response.body().contains(
				"\"resultType\":\"task\""), response.body());
		Assertions.assertTrue(response.body().contains(
				"\"taskId\":\"" + taskId + "\""), response.body());
	}

	private static void assertTaskResult(
			@NonNull HttpResponse<String> response, @NonNull String member,
			@NonNull String value) {
		assertNoStore(response, 200);
		Assertions.assertTrue(response.body().contains(
				"\"status\":\"completed\""), response.body());
		Assertions.assertTrue(response.body().contains("\"structuredContent\":{\""
				+ member + "\":\"" + value + "\"}"), response.body());
	}

	private static void assertRootsInputRequired(
			@NonNull HttpResponse<String> response) {
		assertNoStore(response, 200);
		Assertions.assertTrue(response.body().contains(
				"\"status\":\"input_required\""), response.body());
		Assertions.assertTrue(response.body().contains(
				"\"method\":\"roots/list\""), response.body());
		Assertions.assertTrue(response.body().contains(
				"\"persisted-roots\""), response.body());
	}

	private static void assertInvalidOriginWithoutDisclosure(
			@NonNull HttpResponse<String> response, @NonNull String taskId,
			@NonNull String canary) {
		assertNoStore(response, 500);
		Assertions.assertTrue(response.body().contains("\"code\":-32603"),
				response.body());
		Assertions.assertFalse(response.body().contains(taskId), response.body());
		Assertions.assertFalse(response.body().contains(canary), response.body());
	}

	private static void assertUnknownWithoutDisclosure(
			@NonNull HttpResponse<String> response, @NonNull String taskId) {
		assertNoStore(response, 400);
		Assertions.assertTrue(response.body().contains("\"code\":-32602"),
				response.body());
		Assertions.assertFalse(response.body().contains(taskId), response.body());
	}

	private static void assertEmptyAcknowledgment(
			@NonNull HttpResponse<String> response) {
		assertNoStore(response, 200);
		Assertions.assertTrue(response.body().contains(
				"\"result\":{\"resultType\":\"complete\"}"), response.body());
	}

	private static void assertNoStore(@NonNull HttpResponse<String> response,
			int statusCode) {
		Assertions.assertEquals(statusCode, response.statusCode(), response.body());
		Assertions.assertEquals(List.of("no-store"),
				response.headers().allValues("cache-control"));
	}

	@NonNull
	private static McpTask completedTask(@NonNull String taskId,
			@NonNull McpTaskOrigin taskOrigin, @NonNull String outputMember,
			@NonNull String outputValue, Duration timeToLive,
			@NonNull Instant lastUpdatedAt) {
		return McpTask.withTaskId(taskId, taskOrigin, McpTaskStatus.COMPLETED,
				CREATED_AT, lastUpdatedAt)
				.timeToLive(timeToLive)
				.pollInterval(Duration.ofMillis(250))
				.completedResult(McpCompleteResult.fromToolStructuredContent(
						McpJsonObject.builder()
								.put(outputMember, outputValue).build()))
				.build();
	}

	@NonNull
	private static McpTask inputRequiredTask(@NonNull String taskId,
			@NonNull McpTaskOrigin taskOrigin,
			@NonNull McpInputRequestDeclaration declaration,
			@NonNull String statusMessage) {
		return McpTask.withTaskId(taskId, taskOrigin,
				McpTaskStatus.INPUT_REQUIRED, CREATED_AT, LAST_UPDATED_AT)
				.taskStatusMessage(statusMessage)
				.timeToLive(Duration.ofSeconds(30))
				.pollInterval(Duration.ofMillis(250))
				.addInputRequest("persisted-roots",
						McpInputRequest.fromDeclaration(declaration,
								McpJsonObject.emptyInstance()))
				.build();
	}

	@NonNull
	private static McpTaskOrigin taskOriginWith(@NonNull McpTaskOrigin source,
			@NonNull String name, @NonNull McpJsonValue value) {
		Map<String, McpJsonValue> members = new LinkedHashMap<>(source
				.getPersistedState().getMembers());
		members.put(name, value);
		return McpTaskOrigin.fromPersistedState(
				McpJsonObject.fromMembers(members));
	}

	@NonNull
	private static McpTaskOrigin taskOriginWithout(@NonNull McpTaskOrigin source,
			@NonNull String name) {
		Map<String, McpJsonValue> members = new LinkedHashMap<>(source
				.getPersistedState().getMembers());
		members.remove(name);
		return McpTaskOrigin.fromPersistedState(
				McpJsonObject.fromMembers(members));
	}

	private record FleetArguments(@NonNull String taskId) {
	}

	private record NodeAOutput(@NonNull String nodeAValue) {
	}

	private record NodeBOutput(@NonNull String nodeBValue) {
	}

	@ThreadSafe
	private static final class DurableFleetTaskManager implements McpTaskManager {
		@NonNull
		private final Map<@NonNull String, @NonNull Entry> entries =
				new ConcurrentHashMap<>();
		@NonNull
		private final AtomicInteger updateInvocations = new AtomicInteger();
		@NonNull
		private final AtomicInteger cancelInvocations = new AtomicInteger();

		private void createCompletedTask(@NonNull McpTaskControl taskControl,
				@NonNull String taskId, @NonNull String outputMember,
				@NonNull String outputValue) {
			McpRequestContext requestContext = taskControl.getRequestContext();
			String durableOrigin = taskControl.getTaskOrigin()
					.toPersistedString();
			McpTaskOrigin restoredOrigin = McpTaskOrigin.fromPersistedString(
					durableOrigin);
			McpTask task = completedTask(taskId, restoredOrigin,
					outputMember, outputValue, Duration.ofSeconds(30),
					LAST_UPDATED_AT);
			Entry entry = new Entry(task, requestContext.getEndpoint().getPath(),
					requestContext.getAdmissionIdentity()
							.getAuthorizationPartitionKey());
			if (this.entries.putIfAbsent(taskId, entry) != null)
				throw new IllegalStateException("Duplicate test task ID.");
		}

		@Override
		@NonNull
		public Optional<@NonNull McpTask> findTask(
				@NonNull McpTaskRequestContext context) {
			Entry entry = this.entries.get(context.getTaskId());
			if (entry == null || !entry.authorized(context.getRequestContext()))
				return Optional.empty();
			return Optional.of(entry.task);
		}

		@Override
		public void updateTask(@NonNull McpTaskUpdateContext context)
				throws McpTaskNotFoundException {
			requireAuthorized(context.getTaskId(), context.getRequestContext());
			this.updateInvocations.incrementAndGet();
		}

		@Override
		public void requestTaskCancelation(
				@NonNull McpTaskRequestContext context)
				throws McpTaskNotFoundException {
			requireAuthorized(context.getTaskId(), context.getRequestContext());
			this.cancelInvocations.incrementAndGet();
		}

		private void replaceTask(@NonNull McpTask task) {
			Entry existing = Optional.ofNullable(this.entries.get(task.getTaskId()))
					.orElseThrow();
			this.entries.put(task.getTaskId(), existing.withTask(task));
		}

		private void putTaskLike(@NonNull String sourceTaskId,
				@NonNull McpTask task) {
			Entry source = Optional.ofNullable(this.entries.get(sourceTaskId))
					.orElseThrow();
			if (this.entries.putIfAbsent(task.getTaskId(), source.withTask(task))
					!= null)
				throw new IllegalStateException("Duplicate test task ID.");
		}

		@NonNull
		private McpTask requireTask(@NonNull String taskId) {
			return Optional.ofNullable(this.entries.get(taskId))
					.map(entry -> entry.task)
					.orElseThrow();
		}

		private int taskCount() {
			return this.entries.size();
		}

		private void requireAuthorized(@NonNull String taskId,
				@NonNull McpRequestContext requestContext)
				throws McpTaskNotFoundException {
			Entry entry = this.entries.get(taskId);
			if (entry == null || !entry.authorized(requestContext))
				throw new McpTaskNotFoundException();
		}
	}

	private static final class Entry {
		@NonNull
		private final McpTask task;
		@NonNull
		private final String endpointPath;
		@NonNull
		private final Optional<@NonNull String> authorizationPartitionKey;

		private Entry(@NonNull McpTask task, @NonNull String endpointPath,
				@NonNull Optional<@NonNull String> authorizationPartitionKey) {
			this.task = task;
			this.endpointPath = endpointPath;
			this.authorizationPartitionKey = authorizationPartitionKey;
		}

		private boolean authorized(@NonNull McpRequestContext requestContext) {
			return this.endpointPath.equals(requestContext.getEndpoint().getPath())
					&& this.authorizationPartitionKey.equals(requestContext
					.getAdmissionIdentity().getAuthorizationPartitionKey());
		}

		@NonNull
		private Entry withTask(@NonNull McpTask task) {
			return new Entry(task, this.endpointPath,
					this.authorizationPartitionKey);
		}
	}
}
