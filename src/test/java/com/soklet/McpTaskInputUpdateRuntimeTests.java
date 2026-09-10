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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jspecify.annotations.NonNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real-listener coverage for durable task input and update semantics using the
 * public in-memory manager rather than a recording test double.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpTaskInputUpdateRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String TASKS_EXTENSION_ID =
			"io.modelcontextprotocol/tasks";
	private static final String TOOL_NAME = "tasks.input-update";
	private static final McpInputRequestDeclaration ROOTS_DECLARATION =
			McpInputRequestDeclaration.fromRoots(
					McpInputRequirement.CONDITIONAL);
	private static final McpInputRequestDeclaration FORM_DECLARATION =
			McpInputRequestDeclaration.fromElicitationForm(
					McpInputRequirement.CONDITIONAL);
	private static final McpJsonObject EMPTY_ROOTS_RESPONSE =
			McpJsonObject.builder()
					.put("roots", McpJsonArray.emptyInstance())
					.build();

	@Test
	public void liveUpdatesArePartialIdempotentAndIgnoreUnknownOrSupersededKeys()
			throws Exception {
		McpInMemoryTaskManager taskManager =
				McpTaskManager.fromInMemoryDefaults();
		AtomicReference<String> createdTaskId = new AtomicReference<>();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					McpTask task = taskManager.createTask(
							features.getTaskControl().orElseThrow());
					createdTaskId.set(task.getTaskId());
					return McpTaskCreatedResult
							.<McpJsonObject>fromTaskId(task.getTaskId());
				})
				.addInputRequestDeclaration(ROOTS_DECLARATION)
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion(
						"task-input-update-test", "4.0.0").build())
				.serverInformationIncluded(false)
				.addTool(tool)
				.build();
		McpAdmissionIdentity identity = McpAdmissionIdentity
				.withRateLimitPartitionKey("task-input-rate")
				.authorizationPartitionKey("task-input-owner")
				.build();
		McpServer server = McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.taskManager(taskManager)
				.admissionController(context ->
						McpAdmissionDecision.accepted(identity))
				.host(LOOPBACK)
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.build());

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow()
					.getPort();
			HttpResponse<String> creation = post(port, "tools/call", TOOL_NAME,
					"create", "\"name\":\"" + TOOL_NAME
							+ "\",\"arguments\":{}", true);
			assertSuccess(creation);
			Assertions.assertTrue(creation.body().contains(
					"\"resultType\":\"task\""), creation.body());
			String taskId = createdTaskId.get();
			Assertions.assertNotNull(taskId);
			McpJsonArray persistedDeclarations = Assertions.assertInstanceOf(
					McpJsonArray.class, taskManager.findTask(taskId).orElseThrow()
							.getTaskOrigin().getPersistedState()
							.find("inputRequestDeclarations").orElseThrow());
			Assertions.assertEquals(1, persistedDeclarations.getElements().size());
			McpJsonObject persistedDeclaration = Assertions.assertInstanceOf(
					McpJsonObject.class,
					persistedDeclarations.getElements().get(0));
			Assertions.assertEquals(McpJsonString.fromValue("roots"),
					persistedDeclaration.find("inputRequestType").orElseThrow());
			Assertions.assertEquals(McpJsonString.fromValue("conditional"),
					persistedDeclaration.find("requirement").orElseThrow());

			Map<String, McpInputRequest> firstRound = new LinkedHashMap<>();
			firstRound.put("first", rootsRequest());
			firstRound.put("second", rootsRequest());
			taskManager.requestTaskInput(taskId, firstRound, "Waiting for roots");

			HttpResponse<String> initial = getTask(port, "get-initial", taskId);
			assertSuccess(initial);
			Assertions.assertTrue(initial.body().contains(
					"\"status\":\"input_required\""), initial.body());
			Assertions.assertTrue(initial.body().contains("\"first\":{")
					&& initial.body().contains("\"second\":{"), initial.body());

			assertEmptyAcknowledgement(updateTask(port, "unknown-nonunion", taskId,
					"\"unknown-nonunion\":{\"ignored\":true}"));
			McpTask afterUnknown = taskManager.findTask(taskId).orElseThrow();
			Assertions.assertEquals(McpTaskStatus.INPUT_REQUIRED,
					afterUnknown.getTaskStatus());
			Assertions.assertEquals(List.of("first", "second"),
					List.copyOf(afterUnknown.getInputRequests().keySet()));
			Assertions.assertTrue(taskManager.takeTaskInputResponses(taskId)
					.asMap().isEmpty());

			assertEmptyAcknowledgement(updateTask(port, "partial", taskId,
					"\"first\":{\"roots\":[]},"
							+ "\"second\":{\"ignored\":true},"
							+ "\"unknown\":{\"ignored\":true}"));
			McpTask partial = taskManager.findTask(taskId).orElseThrow();
			Assertions.assertEquals(McpTaskStatus.INPUT_REQUIRED,
					partial.getTaskStatus());
			Assertions.assertEquals(List.of("second"),
					List.copyOf(partial.getInputRequests().keySet()));

			assertEmptyAcknowledgement(updateTask(port, "duplicate", taskId,
					"\"first\":{\"roots\":[{\"uri\":"
							+ "\"file:///must-not-replace\"}]}"));
			McpInputResponses firstResponses =
					taskManager.takeTaskInputResponses(taskId);
			Assertions.assertEquals(EMPTY_ROOTS_RESPONSE,
					firstResponses.find("first").orElseThrow());
			Assertions.assertTrue(firstResponses.find("unknown").isEmpty());
			Assertions.assertTrue(taskManager.takeTaskInputResponses(taskId)
					.asMap().isEmpty());

			assertEmptyAcknowledgement(updateTask(port, "complete-input", taskId,
					"\"second\":{\"roots\":[]}"));
			Assertions.assertEquals(McpTaskStatus.WORKING,
					taskManager.findTask(taskId).orElseThrow().getTaskStatus());
			Assertions.assertEquals(EMPTY_ROOTS_RESPONSE,
					taskManager.takeTaskInputResponses(taskId)
							.find("second").orElseThrow());

			taskManager.requestTaskInput(taskId,
					Map.of("superseded", rootsRequest()), null);
			taskManager.markTaskWorking(taskId, "Worker resumed independently");
			assertEmptyAcknowledgement(updateTask(port, "superseded", taskId,
					"\"superseded\":{\"roots\":[]}"));
			Assertions.assertTrue(taskManager.takeTaskInputResponses(taskId)
					.asMap().isEmpty());

			taskManager.requestTaskInput(taskId,
					Map.of("terminal", rootsRequest()), null);
			taskManager.cancelTask(taskId, "Canceled by worker");
			assertEmptyAcknowledgement(updateTask(port, "terminal", taskId,
					"\"terminal\":{\"roots\":[]}"));
			Assertions.assertEquals(McpTaskStatus.CANCELED,
					taskManager.findTask(taskId).orElseThrow().getTaskStatus());
			Assertions.assertTrue(taskManager.takeTaskInputResponses(taskId)
					.asMap().isEmpty());

			HttpResponse<String> undeclaredCreation = post(port, "tools/call",
					TOOL_NAME, "create-undeclared", "\"name\":\"" + TOOL_NAME
							+ "\",\"arguments\":{}", true);
			assertSuccess(undeclaredCreation);
			String undeclaredTaskId = createdTaskId.get();
			Assertions.assertNotEquals(taskId, undeclaredTaskId);
			taskManager.requestTaskInput(undeclaredTaskId,
					Map.of("undeclared", formRequest()), null);
			HttpResponse<String> undeclared = getTask(port, "undeclared",
					undeclaredTaskId);
			Assertions.assertEquals(500, undeclared.statusCode(), undeclared.body());
			Assertions.assertTrue(undeclared.body().contains("\"code\":-32603"),
					undeclared.body());
			Assertions.assertFalse(undeclared.body().contains("-32021"),
					undeclared.body());
			Assertions.assertFalse(undeclared.body().contains(undeclaredTaskId),
					undeclared.body());
		} finally {
			soklet.close();
		}
	}

	@NonNull
	private static McpInputRequest rootsRequest() {
		return McpInputRequest.fromDeclaration(ROOTS_DECLARATION,
				McpJsonObject.emptyInstance());
	}

	@NonNull
	private static McpInputRequest formRequest() {
		return McpInputRequest.fromDeclaration(FORM_DECLARATION,
				McpJsonObject.builder()
						.put("mode", "form")
						.put("message", "Undeclared input must not escape")
						.put("requestedSchema", McpJsonObject.builder()
								.put("type", "object")
								.put("properties", McpJsonObject.emptyInstance())
								.build())
						.build());
	}

	@NonNull
	private static HttpResponse<String> getTask(int port,
			@NonNull String requestId, @NonNull String taskId) throws Exception {
		return post(port, "tasks/get", taskId, requestId,
				"\"taskId\":\"" + taskId + "\"", true);
	}

	@NonNull
	private static HttpResponse<String> updateTask(int port,
			@NonNull String requestId, @NonNull String taskId,
			@NonNull String inputResponses) throws Exception {
		return post(port, "tasks/update", taskId, requestId,
				"\"taskId\":\"" + taskId + "\",\"inputResponses\":{"
						+ inputResponses + "}", true);
	}

	@NonNull
	private static HttpResponse<String> post(int port, @NonNull String method,
			@NonNull String operationName, @NonNull String requestId,
			@NonNull String fields, boolean rootsCapable) throws Exception {
		String capabilities = "{\"extensions\":{\"" + TASKS_EXTENSION_ID
				+ "\":{}}" + (rootsCapable ? ",\"roots\":{}" : "") + "}";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"" + method + "\",\"params\":{"
				+ fields + ",\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":"
				+ capabilities + "}}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method)
				.header("Mcp-Name", operationName)
				.POST(HttpRequest.BodyPublishers.ofString(body,
						StandardCharsets.UTF_8))
				.build();
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request, HttpResponse.BodyHandlers.ofString(
						StandardCharsets.UTF_8));
	}

	private static void assertEmptyAcknowledgement(
			@NonNull HttpResponse<String> response) {
		assertSuccess(response);
		Assertions.assertTrue(response.body().contains(
				"\"result\":{\"resultType\":\"complete\"}"),
				response.body());
	}

	private static void assertSuccess(@NonNull HttpResponse<String> response) {
		Assertions.assertEquals(200, response.statusCode(), response.body());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
	}
}
