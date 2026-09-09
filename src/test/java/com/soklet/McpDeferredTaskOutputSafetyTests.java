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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hostile-path coverage for typed output retrieved from durable MCP tasks.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpDeferredTaskOutputSafetyTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String TASKS_EXTENSION_ID =
			"io.modelcontextprotocol/tasks";
	private static final String TOOL_NAME = "tasks.typed-output-safety";
	private static final String TASK_ID = "typed-output-task";
	private static final String ORIGINAL_CANARY =
			"UNSANITIZED-DEFERRED-OUTPUT-MUST-NOT-LEAK";
	private static final String MISMATCH_CANARY =
			"SCHEMA-MISMATCH-MUST-NOT-LEAK";
	private static final String THROW_CANARY =
			"SANITIZER-THROWABLE-MUST-NOT-LEAK";
	private static final String DEPTH_CANARY =
			"DEPTH-LIMIT-OUTPUT-MUST-NOT-LEAK";
	private static final String NODE_CANARY =
			"NODE-LIMIT-OUTPUT-MUST-NOT-LEAK";
	private static final String SIZE_CANARY =
			"SIZE-LIMIT-OUTPUT-MUST-NOT-LEAK";
	private static final Instant NOW =
			Instant.parse("2026-09-09T12:00:00Z");

	@Test
	public void typedTaskOriginDrivesSanitizedDeferredOutputValidation()
			throws Exception {
		AtomicReference<SanitizerMode> sanitizerMode =
				new AtomicReference<>(SanitizerMode.VALID);
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		List<String> stages = new CopyOnWriteArrayList<>();
		TaskManager taskManager = new TaskManager();
		McpToolRegistration<TaskArguments> tool = tool(taskManager, stages);
		McpToolOutputSanitizer sanitizer = sanitizer(sanitizerMode,
				sanitizerInvocations);
		McpHandlerInterceptor interceptor = (context, features, continuation) -> {
			stages.add("interceptor-before");
			McpOperationResult result = continuation.proceed();
			stages.add("interceptor-after");
			return result;
		};
		McpServer server = server(tool, taskManager, interceptor, sanitizer);
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);
			HttpResponse<String> creation = createTask(port);
			assertSuccess(creation);
			assertContains(creation.body(), "\"resultType\":\"task\"");
			Assertions.assertEquals(List.of("interceptor-before", "handler",
					"interceptor-after"), stages);
			Assertions.assertEquals(0, sanitizerInvocations.get(),
					"Task creation must not render or sanitize terminal output.");

			McpTaskOrigin taskOrigin = taskManager.taskOrigin.orElseThrow();
			Assertions.assertEquals(
					tool.getOutputSchema().orElseThrow().getDocument(),
					taskOrigin.getPersistedState().find("outputSchema")
							.orElseThrow());
			Assertions.assertEquals(McpJsonObject.builder()
					.put("request", "retained-at-origin").build(),
					taskOrigin.getPersistedState().find("rawArguments")
							.orElseThrow());

			HttpResponse<String> completed = getTask(port, "valid-result");
			assertSuccess(completed);
			assertContains(completed.body(),
					"\"structuredContent\":{\"message\":\"sanitized\","
							+ "\"count\":42,\"chunks\":[]}");
			assertContains(completed.body(),
					"{\\\"message\\\":\\\"sanitized\\\","
							+ "\\\"count\\\":42,\\\"chunks\\\":[]}");
			Assertions.assertFalse(completed.body().contains(ORIGINAL_CANARY),
					completed.body());
			Assertions.assertEquals(1, sanitizerInvocations.get());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void everyDeferredSanitizerAndOutputFailureIsFailClosed()
			throws Exception {
		AtomicReference<SanitizerMode> sanitizerMode =
				new AtomicReference<>(SanitizerMode.VALID);
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		TaskManager taskManager = new TaskManager();
		McpToolRegistration<TaskArguments> tool = tool(taskManager,
				new CopyOnWriteArrayList<>());
		McpServer server = server(tool, taskManager,
				McpHandlerInterceptor.passThroughInstance(),
				sanitizer(sanitizerMode, sanitizerInvocations));
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);
			assertSuccess(createTask(port));

			for (SanitizerMode mode : List.of(SanitizerMode.PASS_THROUGH,
					SanitizerMode.SCHEMA_MISMATCH, SanitizerMode.NULL,
					SanitizerMode.THROW, SanitizerMode.DEPTH_LIMIT,
					SanitizerMode.NODE_LIMIT, SanitizerMode.SIZE_LIMIT)) {
				sanitizerMode.set(mode);
				String requestId = mode.name().toLowerCase();
				HttpResponse<String> response = getTask(port, requestId);
				assertFixedInternalError(response, requestId);
				for (String canary : List.of(ORIGINAL_CANARY,
						MISMATCH_CANARY, THROW_CANARY, DEPTH_CANARY,
						NODE_CANARY, SIZE_CANARY, TASK_ID))
					Assertions.assertFalse(response.body().contains(canary),
							response.body());
			}

			sanitizerMode.set(SanitizerMode.VALID);
			HttpResponse<String> recovered = getTask(port, "recovered");
			assertSuccess(recovered);
			assertContains(recovered.body(), "\"message\":\"sanitized\"");
			Assertions.assertEquals(8, sanitizerInvocations.get());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void typedDeferredToolErrorsMayDeliberatelyOmitStructuredOutput()
			throws Exception {
		AtomicReference<SanitizerMode> sanitizerMode =
				new AtomicReference<>(SanitizerMode.ERROR);
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		TaskManager taskManager = new TaskManager();
		McpToolRegistration<TaskArguments> tool = tool(taskManager,
				new CopyOnWriteArrayList<>());
		McpServer server = server(tool, taskManager,
				McpHandlerInterceptor.passThroughInstance(),
				sanitizer(sanitizerMode, sanitizerInvocations));
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);
			assertSuccess(createTask(port));
			HttpResponse<String> response = getTask(port, "error-result");
			assertSuccess(response);
			assertContains(response.body(), "DEFERRED-SAFE-ERROR");
			assertContains(response.body(), "\"isError\":true");
			Assertions.assertFalse(response.body()
					.contains("\"structuredContent\""), response.body());
			Assertions.assertFalse(response.body().contains(ORIGINAL_CANARY),
					response.body());
			Assertions.assertEquals(1, sanitizerInvocations.get());
		} finally {
			soklet.close();
		}
	}

	private static McpToolRegistration<TaskArguments> tool(
			@NonNull TaskManager taskManager, @NonNull List<@NonNull String> stages) {
		return McpToolRegistration.withName(TOOL_NAME)
				.argumentAndOutputTypes(TaskArguments.class, TaskOutput.class)
				.operationHandler((request, arguments, features) -> {
					stages.add("handler");
					Assertions.assertEquals("retained-at-origin",
							arguments.getConvertedArguments().request());
					McpTaskOrigin taskOrigin = features.getTaskControl()
							.orElseThrow().getTaskOrigin();
					taskManager.taskOrigin = Optional.of(taskOrigin);
					taskManager.task = Optional.of(McpTask.withTaskId(TASK_ID,
							taskOrigin, McpTaskStatus.COMPLETED, NOW, NOW)
							.completedResult(McpCompleteResult
									.fromToolStructuredContent(McpJsonObject.builder()
											.put("rawSecret", ORIGINAL_CANARY)
											.build()))
							.build());
					return McpTaskCreatedResult.<TaskOutput>fromTaskId(TASK_ID);
				})
				.build();
	}

	private static McpToolOutputSanitizer sanitizer(
			@NonNull AtomicReference<SanitizerMode> mode,
			@NonNull AtomicInteger invocations) {
		return (request, toolName, rawArguments, output) -> {
			invocations.incrementAndGet();
			Assertions.assertEquals(McpOperationType.TASKS_GET,
					request.getOperationType());
			Assertions.assertEquals(TOOL_NAME, toolName);
			Assertions.assertEquals(McpJsonObject.builder()
					.put("request", "retained-at-origin").build(), rawArguments);
			return switch (mode.get()) {
				case VALID -> McpToolOutput.fromStructuredContent(validOutput());
				case ERROR -> McpToolOutput
						.fromErrorText("DEFERRED-SAFE-ERROR");
				case PASS_THROUGH -> output;
				case SCHEMA_MISMATCH -> McpToolOutput.fromStructuredContent(
						McpJsonObject.builder()
								.put("message", MISMATCH_CANARY)
								.put("count", "not-an-integer")
								.put("chunks", McpJsonArray.emptyInstance())
								.build());
				case NULL -> null;
				case THROW -> throw new IllegalStateException(THROW_CANARY);
				case DEPTH_LIMIT -> McpToolOutput.fromStructuredContent(
						deeplyNestedOutput());
				case NODE_LIMIT -> McpToolOutput.fromStructuredContent(
						nodeLimitOutput());
				case SIZE_LIMIT -> McpToolOutput.fromStructuredContent(
						sizeLimitOutput());
			};
		};
	}

	private static McpJsonObject validOutput() {
		return McpJsonObject.builder()
				.put("message", "sanitized")
				.put("count", 42)
				.put("chunks", McpJsonArray.emptyInstance())
				.build();
	}

	private static McpJsonValue deeplyNestedOutput() {
		McpJsonValue value = McpJsonString.fromValue(DEPTH_CANARY);
		for (int depth = 0; depth < 512; depth++)
			value = McpJsonArray.fromElements(List.of(value));
		return value;
	}

	private static McpJsonObject nodeLimitOutput() {
		McpJsonArray chunks = McpJsonArray.fromElements(Collections.nCopies(
				100_000, McpJsonString.fromValue("")));
		return McpJsonObject.builder()
				.put("message", NODE_CANARY)
				.put("count", 1)
				.put("chunks", chunks)
				.build();
	}

	private static McpJsonObject sizeLimitOutput() {
		McpJsonString chunk = McpJsonString.fromValue("x".repeat(900_000));
		return McpJsonObject.builder()
				.put("message", SIZE_CANARY)
				.put("count", 1)
				.put("chunks", McpJsonArray.fromElements(
						Collections.nCopies(5, chunk)))
				.build();
	}

	private static McpServer server(@NonNull McpToolRegistration<?> tool,
			@NonNull McpTaskManager taskManager,
			@NonNull McpHandlerInterceptor interceptor,
			@NonNull McpToolOutputSanitizer sanitizer) {
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion(
						"deferred-task-output-safety-test", "4.0.0").build())
				.addTool(tool)
				.build();
		return McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.host(LOOPBACK)
				.taskManager(taskManager)
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.handlerInterceptor(interceptor)
				.toolOutputSanitizer(sanitizer)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
	}

	private static Soklet managedSoklet(@NonNull McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.build());
	}

	private static int boundPort(@NonNull McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	private static HttpResponse<String> createTask(int port) throws Exception {
		return post(port, "tools/call", TOOL_NAME,
				"{\"jsonrpc\":\"2.0\",\"id\":\"create-task\","
						+ "\"method\":\"tools/call\",\"params\":{"
						+ metadata() + ",\"name\":\"" + TOOL_NAME + "\","
						+ "\"arguments\":{\"request\":\"retained-at-origin\"}}}");
	}

	private static HttpResponse<String> getTask(int port,
			@NonNull String requestId) throws Exception {
		return post(port, "tasks/get", TASK_ID,
				"{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId + "\","
						+ "\"method\":\"tasks/get\",\"params\":{"
						+ metadata() + ",\"taskId\":\"" + TASK_ID + "\"}}");
	}

	private static String metadata() {
		return "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{"
				+ "\"extensions\":{\"" + TASKS_EXTENSION_ID + "\":{}}}}";
	}

	private static HttpResponse<String> post(int port, @NonNull String method,
			@NonNull String operationName, @NonNull String body) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method)
				.header("Mcp-Name", operationName)
				.POST(HttpRequest.BodyPublishers.ofString(body,
						StandardCharsets.UTF_8))
				.build();
		return HttpClient.newHttpClient().send(request,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static void assertSuccess(
			@NonNull HttpResponse<String> response) {
		Assertions.assertEquals(200, response.statusCode(), response.body());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
	}

	private static void assertFixedInternalError(
			@NonNull HttpResponse<String> response, @NonNull String requestId) {
		Assertions.assertEquals(500, response.statusCode(), response.body());
		Assertions.assertEquals(
				"{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
						+ "\",\"error\":{\"code\":-32603,"
						+ "\"message\":\"Internal error\"}}",
				response.body());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
	}

	private static void assertContains(@NonNull String value,
			@NonNull String substring) {
		Assertions.assertTrue(value.contains(substring), value);
	}

	private enum SanitizerMode {
		VALID,
		ERROR,
		PASS_THROUGH,
		SCHEMA_MISMATCH,
		NULL,
		THROW,
		DEPTH_LIMIT,
		NODE_LIMIT,
		SIZE_LIMIT
	}

	private static final class TaskManager implements McpTaskManager {
		@NonNull
		private volatile Optional<@NonNull McpTaskOrigin> taskOrigin =
				Optional.empty();
		@NonNull
		private volatile Optional<@NonNull McpTask> task = Optional.empty();

		@Override
		@NonNull
		public Optional<@NonNull McpTask> findTask(
				@NonNull McpTaskRequestContext context) {
			return this.task.filter(candidate -> candidate.getTaskId()
					.equals(context.getTaskId()));
		}

		@Override
		public void updateTask(@NonNull McpTaskUpdateContext context) {
		}

		@Override
		public void requestTaskCancelation(
				@NonNull McpTaskRequestContext context) {
		}
	}

	private record TaskArguments(@NonNull String request) {
	}

	private record TaskOutput(@NonNull String message, @NonNull Integer count,
			@NonNull List<@NonNull String> chunks) {
	}
}
