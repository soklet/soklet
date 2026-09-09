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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Public real-listener coverage for the MCP Tasks vertical.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpTasksPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String TASKS_EXTENSION_ID =
			"io.modelcontextprotocol/tasks";
	private static final String TOOL_NAME = "tasks.vertical";
	private static final String FIND_FAILURE_CANARY =
			"find-manager-failure-secret";
	private static final String MUTATION_FAILURE_CANARY =
			"mutation-manager-failure-secret";
	private static final Instant CREATED_AT =
			Instant.parse("2026-09-01T12:00:00Z");
	private static final Instant LAST_UPDATED_AT =
			Instant.parse("2026-09-01T12:01:00Z");
	private static final Duration TASK_TIME_TO_LIVE = Duration.ofMinutes(1);
	private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
	private static final Object PRINCIPAL = new Object();
	private static final Object APPLICATION_CONTEXT = new Object();
	private static final McpAdmissionIdentity ADMISSION_IDENTITY =
			McpAdmissionIdentity.withRateLimitPartitionKey("tasks-rate-limit")
					.authorizationPartitionKey("tasks-authorization")
					.principal(PRINCIPAL)
					.applicationContext(APPLICATION_CONTEXT)
					.build();
	private static final McpInputRequestDeclaration ROOTS_DECLARATION =
			McpInputRequestDeclaration.fromRoots(
					McpInputRequirement.CONDITIONAL);

	@Test
	public void creationAndEveryDetailedTaskStateHaveExactWireShapes()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpServer server = taskServer(taskManager, handlerInvocations,
				McpHandlerInterceptor.passThroughInstance(), new AtomicInteger());
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);

			HttpResponse<String> creation = callTool(port, "create-working",
					"task-working", false, true);
			assertNoStore(creation, 200);
			Assertions.assertEquals(taskResponse("create-working",
					taskManager.getTask("task-working"), true, ""),
					creation.body());
			Assertions.assertEquals(1, handlerInvocations.get());
			Assertions.assertEquals(1, taskManager.findInvocations.get(),
					"Creation must prove that the task is already durable.");

			assertTaskGet(port, "get-working",
					taskManager.getTask("task-working"), "");

			McpTaskOrigin taskOrigin = taskManager.taskOrigin.orElseThrow();
			McpTask inputRequired = task("task-input", taskOrigin,
					McpTaskStatus.INPUT_REQUIRED);
			taskManager.putTask(inputRequired);
			HttpResponse<String> missingRoots = callTask(port, "tasks/get",
					"get-input-missing-roots", inputRequired.getTaskId(), true,
					false);
			assertNoStore(missingRoots, 400);
			Assertions.assertEquals(
					"{\"jsonrpc\":\"2.0\","
							+ "\"id\":\"get-input-missing-roots\",\"error\":{"
							+ "\"code\":-32021,\"message\":"
							+ "\"Missing required client capability\",\"data\":{"
							+ "\"requiredCapabilities\":{\"roots\":{}}}}}",
					missingRoots.body());
			assertTaskGet(port, "get-input", inputRequired,
					",\"inputRequests\":{\"approval\":{"
							+ "\"method\":\"roots/list\",\"params\":{}}}");

			McpTask completed = task("task-completed", taskOrigin,
					McpTaskStatus.COMPLETED);
			taskManager.putTask(completed);
			assertTaskGet(port, "get-completed", completed,
					",\"result\":{\"content\":[{\"type\":\"text\","
							+ "\"text\":\"completed-output\"}],"
							+ "\"resultType\":\"complete\",\"_meta\":{"
							+ "\"com.example/completed\":\"nested\"}}");

			McpTask failed = task("task-failed", taskOrigin,
					McpTaskStatus.FAILED);
			taskManager.putTask(failed);
			assertTaskGet(port, "get-failed", failed,
					",\"error\":{\"code\":41001,"
							+ "\"message\":\"task-failure\",\"data\":{"
							+ "\"reason\":\"failure-detail\"}}");

			McpTask canceled = task("task-canceled", taskOrigin,
					McpTaskStatus.CANCELED);
			taskManager.putTask(canceled);
			assertTaskGet(port, "get-canceled", canceled, "");

			HttpResponse<String> terminalCreation = callTool(port,
					"create-terminal", "task-terminal", true, true);
			assertNoStore(terminalCreation, 200);
			McpTask terminalTask = taskManager.getTask("task-terminal");
			Assertions.assertEquals(taskResponse("create-terminal",
					terminalTask, true, ""), terminalCreation.body());
			Assertions.assertFalse(terminalCreation.body().contains(
					"\"result\":{\"content\""), terminalCreation.body());
			assertTaskGet(port, "get-terminal", terminalTask,
					",\"result\":{\"content\":[{\"type\":\"text\","
							+ "\"text\":\"completed-output\"}],"
							+ "\"resultType\":\"complete\",\"_meta\":{"
							+ "\"com.example/completed\":\"nested\"}}");
		} finally {
			soklet.close();
		}
	}

	@Test
	public void updateAndCancelPreserveAdmissionIdentityAndReturnEmptyAcks()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		McpServer server = taskServer(taskManager, new AtomicInteger(),
				McpHandlerInterceptor.passThroughInstance(), new AtomicInteger());
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);

			HttpResponse<String> update = callTaskUpdate(port, "update-task",
					"task-actions", true);
			assertNoStore(update, 200);
			Assertions.assertEquals(
					"{\"jsonrpc\":\"2.0\",\"id\":\"update-task\","
							+ "\"result\":{\"resultType\":\"complete\"}}",
					update.body());
			McpTaskUpdateContext updateContext =
					taskManager.updateContext.get();
			Assertions.assertNotNull(updateContext);
			Assertions.assertEquals("task-actions", updateContext.getTaskId());
			Assertions.assertEquals(McpJsonObject.builder()
					.put("action", "decline").build(),
					updateContext.getInputResponses().find("approval")
							.orElseThrow());
			assertIdentity(updateContext.getRequestContext());
			Assertions.assertEquals(Optional.of("task-actions"),
					updateContext.getRequestContext().getOperationName());
			Assertions.assertEquals(McpOperationType.TASKS_UPDATE,
					updateContext.getRequestContext().getOperationType());

			HttpResponse<String> cancel = callTask(port, "tasks/cancel",
					"cancel-task", "task-actions", true);
			assertNoStore(cancel, 200);
			Assertions.assertEquals(
					"{\"jsonrpc\":\"2.0\",\"id\":\"cancel-task\","
							+ "\"result\":{\"resultType\":\"complete\"}}",
					cancel.body());
			McpTaskRequestContext cancelContext =
					taskManager.cancelContext.get();
			Assertions.assertNotNull(cancelContext);
			Assertions.assertEquals("task-actions", cancelContext.getTaskId());
			assertIdentity(cancelContext.getRequestContext());
			Assertions.assertEquals(Optional.of("task-actions"),
					cancelContext.getRequestContext().getOperationName());
			Assertions.assertEquals(McpOperationType.TASKS_CANCEL,
					cancelContext.getRequestContext().getOperationType());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void lookupAndMutationFailuresAreFailClosedAndDoNotDiscloseTaskIds()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		McpServer server = taskServer(taskManager, new AtomicInteger(),
				McpHandlerInterceptor.passThroughInstance(), new AtomicInteger());
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);
			assertNoStore(callTool(port, "seed-origin", "seed-task", false,
					true), 200);

			String privateTaskId = "private-task-id-secret";
			taskManager.findMode = FindMode.EMPTY;
			HttpResponse<String> unknown = callTask(port, "tasks/get",
					"private-lookup", privateTaskId, true);
			taskManager.findMode = FindMode.UNAUTHORIZED;
			HttpResponse<String> unauthorized = callTask(port, "tasks/get",
					"private-lookup", privateTaskId, true);
			assertInvalidParamsWithoutTaskDisclosure(unknown, privateTaskId);
			assertInvalidParamsWithoutTaskDisclosure(unauthorized, privateTaskId);
			Assertions.assertEquals(unknown.body(), unauthorized.body(),
					"Unknown and unauthorized tasks must be indistinguishable.");

			for (FindMode findMode : List.of(FindMode.NULL_OPTIONAL,
					FindMode.WRONG_ID, FindMode.WRONG_ORIGIN, FindMode.THROW)) {
				taskManager.findMode = findMode;
				HttpResponse<String> response = callTask(port, "tasks/get",
						"hostile-" + findMode.name().toLowerCase(Locale.ROOT),
						privateTaskId, true);
				assertInternalErrorWithoutDisclosure(response, privateTaskId,
						FIND_FAILURE_CANARY);
			}

			taskManager.findMode = FindMode.NORMAL;
			assertNoStore(callTask(port, "tasks/get", "listener-recovered",
					"seed-task", true), 200);

			taskManager.mutationMode = MutationMode.NOT_FOUND;
			assertInvalidParamsWithoutTaskDisclosure(callTaskUpdate(port,
					"update-private", privateTaskId, true), privateTaskId);
			assertInvalidParamsWithoutTaskDisclosure(callTask(port,
					"tasks/cancel", "cancel-private", privateTaskId, true),
					privateTaskId);

			taskManager.mutationMode = MutationMode.THROW;
			assertInternalErrorWithoutDisclosure(callTaskUpdate(port,
					"update-hostile", privateTaskId, true), privateTaskId,
					MUTATION_FAILURE_CANARY);
			assertInternalErrorWithoutDisclosure(callTask(port,
					"tasks/cancel", "cancel-hostile", privateTaskId, true),
					privateTaskId, MUTATION_FAILURE_CANARY);
		} finally {
			soklet.close();
		}
	}

	@Test
	public void taskCreationFailsClosedForEveryBrokenDurabilityResponse()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		McpServer server = taskServer(taskManager, new AtomicInteger(),
				McpHandlerInterceptor.passThroughInstance(), new AtomicInteger());
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);
			for (FindMode findMode : List.of(FindMode.EMPTY,
					FindMode.NULL_OPTIONAL, FindMode.WRONG_ID,
					FindMode.WRONG_ORIGIN, FindMode.THROW)) {
				taskManager.findMode = findMode;
				String suffix = findMode.name().toLowerCase(Locale.ROOT);
				String taskId = "creation-secret-" + suffix;
				HttpResponse<String> response = callTool(port,
						"creation-" + suffix, taskId, false, true);
				assertInternalErrorWithoutDisclosure(response, taskId,
						FIND_FAILURE_CANARY);
			}

			taskManager.findMode = FindMode.NORMAL;
			assertNoStore(callTool(port, "creation-recovered",
					"creation-valid", false, true), 200);
		} finally {
			soklet.close();
		}
	}

	@Test
	public void capabilityAndTaskNamespacePreflightRemainFailClosed()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		AtomicInteger admissions = new AtomicInteger();
		McpServer configuredServer = taskServer(taskManager, new AtomicInteger(),
				McpHandlerInterceptor.passThroughInstance(), admissions);
		Soklet configuredSoklet = managedSoklet(configuredServer);

		try {
			configuredSoklet.start();
			int port = boundPort(configuredServer);
			HttpResponse<String> missingCapability = callTask(port, "tasks/get",
					"missing-capability", "capability-task", false);
			assertNoStore(missingCapability, 400);
			Assertions.assertEquals(
					"{\"jsonrpc\":\"2.0\",\"id\":\"missing-capability\","
							+ "\"error\":{\"code\":-32021,\"message\":"
							+ "\"Missing required client capability\",\"data\":{"
							+ "\"requiredCapabilities\":{\"extensions\":{\""
							+ TASKS_EXTENSION_ID + "\":{}}}}}}",
					missingCapability.body());
			Assertions.assertEquals(0, admissions.get(),
					"Missing Tasks support must fail before admission.");
			Assertions.assertEquals(0, taskManager.findInvocations.get());

			for (String obsoleteMethod : List.of("tasks/list", "tasks/result")) {
				HttpResponse<String> obsolete = callObsoleteTaskMethod(port,
						obsoleteMethod);
				assertMethodNotFound(obsolete);
			}
			Assertions.assertEquals(0, admissions.get(),
					"The reserved Tasks namespace must fail before admission.");
		} finally {
			configuredSoklet.close();
		}

		McpEndpoint endpoint = endpoint(new ScriptedTaskManager(),
				new AtomicInteger());
		McpServer unconfiguredServer = server(endpoint, Optional.empty(),
				McpHandlerInterceptor.passThroughInstance(), new AtomicInteger());
		Soklet unconfiguredSoklet = managedSoklet(unconfiguredServer);
		try {
			unconfiguredSoklet.start();
			HttpResponse<String> noManager = callTask(boundPort(unconfiguredServer),
					"tasks/get", "no-manager", "unconfigured-task", true);
			assertMethodNotFound(noManager);
		} finally {
			unconfiguredSoklet.close();
		}
	}

	@Test
	public void interceptorTaskCreationStillValidatesTypedArgumentsBeforeLookup()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpToolRegistration<RequiredArguments> tool = McpToolRegistration
				.withName("tasks.typed-interceptor")
				.argumentType(RequiredArguments.class)
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText("unexpected");
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion(
						"tasks-typed-interceptor-test", "4.0.0").build())
				.addTool(tool)
				.build();
		McpHandlerInterceptor interceptor = (context, features, continuation) ->
				McpTaskCreatedResult.<String>fromTaskId("typed-task");
		McpServer server = server(endpoint, Optional.of(taskManager), interceptor,
				new AtomicInteger());
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			HttpResponse<String> response = post(boundPort(server), "tools/call",
					"tasks.typed-interceptor",
					"{\"jsonrpc\":\"2.0\",\"id\":\"typed-invalid\","
							+ "\"method\":\"tools/call\",\"params\":{"
							+ taskMetadata(true) + ",\"name\":"
							+ "\"tasks.typed-interceptor\",\"arguments\":{}}}");
			assertNoStore(response, 400);
			Assertions.assertEquals(
					"{\"jsonrpc\":\"2.0\",\"id\":\"typed-invalid\","
							+ "\"error\":{\"code\":-32602,"
							+ "\"message\":\"Invalid params\"}}",
					response.body());
			Assertions.assertEquals(0, handlerInvocations.get());
			Assertions.assertEquals(0, taskManager.findInvocations.get(),
					"A task lookup must not precede typed argument validation.");
		} finally {
			soklet.close();
		}
	}

	@Test
	public void completedTaskUsesOriginalToolSanitizerAndMirroringContract()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("tasks.sanitized")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					McpTaskOrigin taskOrigin = features.getTaskControl()
							.orElseThrow().getTaskOrigin();
					taskManager.taskOrigin = Optional.of(taskOrigin);
					McpTask completed = McpTask.withTaskId("sanitized-task",
							taskOrigin, McpTaskStatus.COMPLETED,
							CREATED_AT, LAST_UPDATED_AT)
							.timeToLive(TASK_TIME_TO_LIVE)
							.completedResult(McpCompleteResult
									.fromToolStructuredContent(McpJsonObject.builder()
											.put("unsafe", "original").build())
									.withMetadata(McpJsonObject.builder()
											.put("com.example/completed", "nested")
											.build()))
							.metadata(McpJsonObject.builder()
									.put("com.example/task-metadata", "completed")
									.build())
							.build();
					taskManager.putTask(completed);
					return McpTaskCreatedResult
							.<McpJsonObject>fromTaskId("sanitized-task");
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion(
						"tasks-sanitizer-test", "4.0.0").build())
				.addTool(tool)
				.build();
		McpToolOutputSanitizer sanitizer = (request, toolName, rawArguments,
				output) -> {
			sanitizerInvocations.incrementAndGet();
			Assertions.assertEquals(McpOperationType.TASKS_GET,
					request.getOperationType());
			assertIdentity(request);
			Assertions.assertEquals("tasks.sanitized", toolName);
			Assertions.assertEquals(McpJsonObject.builder()
					.put("originalArgument", "retained").build(), rawArguments);
			Assertions.assertEquals(McpJsonObject.builder()
					.put("unsafe", "original").build(),
					output.getStructuredContent().orElseThrow());
			return output.toBuilder()
					.structuredContent(McpJsonObject.builder()
							.put("safe", "sanitized").build())
					.build();
		};
		McpServer server = server(endpoint, Optional.of(taskManager),
				McpHandlerInterceptor.passThroughInstance(), new AtomicInteger(),
				sanitizer);
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);
			HttpResponse<String> creation = post(port, "tools/call",
					"tasks.sanitized",
					"{\"jsonrpc\":\"2.0\",\"id\":\"sanitized-create\","
							+ "\"method\":\"tools/call\",\"params\":{"
							+ taskMetadata(true) + ",\"name\":"
							+ "\"tasks.sanitized\",\"arguments\":{"
							+ "\"originalArgument\":\"retained\"}}}");
			assertNoStore(creation, 200);
			Assertions.assertTrue(creation.body().contains(
					"\"resultType\":\"task\""), creation.body());
			Assertions.assertEquals(0, sanitizerInvocations.get(),
					"A base task handle must not render completed output.");

			HttpResponse<String> completed = callTask(port, "tasks/get",
					"sanitized-get", "sanitized-task", true);
			assertNoStore(completed, 200);
			Assertions.assertEquals(1, sanitizerInvocations.get());
			Assertions.assertTrue(completed.body().contains(
					"\"content\":[{\"type\":\"text\",\"text\":"
							+ "\"{\\\"safe\\\":\\\"sanitized\\\"}\"}]"),
					completed.body());
			Assertions.assertTrue(completed.body().contains(
					"\"structuredContent\":{\"safe\":\"sanitized\"}"),
					completed.body());
			Assertions.assertFalse(completed.body().contains("unsafe"),
					completed.body());
			Assertions.assertTrue(completed.body().contains(
					"\"resultType\":\"complete\",\"_meta\":{"
							+ "\"com.example/completed\":\"nested\"}"),
					completed.body());
		} finally {
			soklet.close();
		}
	}

	private static McpEndpoint endpoint(@NonNull ScriptedTaskManager taskManager,
			@NonNull AtomicInteger handlerInvocations) {
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					McpJsonObject rawArguments = arguments.getRawArguments();
					String taskId = ((McpJsonString) rawArguments.getMembers()
							.get("taskId")).getValue();
					boolean completeImmediately = rawArguments.getMembers()
							.get("completeImmediately")
							instanceof McpJsonBoolean bool && bool.getValue();
					McpTaskOrigin taskOrigin = features.getTaskControl()
							.orElseThrow().getTaskOrigin();
					taskManager.taskOrigin = Optional.of(taskOrigin);
					taskManager.putTask(task(taskId, taskOrigin,
							completeImmediately ? McpTaskStatus.COMPLETED
									: McpTaskStatus.WORKING));
					return McpTaskCreatedResult
							.<McpJsonObject>fromTaskId(taskId);
				})
				.addInputRequestDeclaration(ROOTS_DECLARATION)
				.structuredContentMirroredAsText(false)
				.build();
		return McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion(
						"tasks-public-runtime-test", "4.0.0").build())
				.addTool(tool)
				.build();
	}

	private static McpServer taskServer(
			@NonNull ScriptedTaskManager taskManager,
			@NonNull AtomicInteger handlerInvocations,
			@NonNull McpHandlerInterceptor handlerInterceptor,
			@NonNull AtomicInteger admissions) {
		return server(endpoint(taskManager, handlerInvocations),
				Optional.of(taskManager), handlerInterceptor, admissions);
	}

	private static McpServer server(@NonNull McpEndpoint endpoint,
			@NonNull Optional<@NonNull McpTaskManager> taskManager,
			@NonNull McpHandlerInterceptor handlerInterceptor,
			@NonNull AtomicInteger admissions) {
		return server(endpoint, taskManager, handlerInterceptor, admissions,
				McpToolOutputSanitizer.passThroughInstance());
	}

	private static McpServer server(@NonNull McpEndpoint endpoint,
			@NonNull Optional<@NonNull McpTaskManager> taskManager,
			@NonNull McpHandlerInterceptor handlerInterceptor,
			@NonNull AtomicInteger admissions,
			@NonNull McpToolOutputSanitizer toolOutputSanitizer) {
		McpServer.Builder builder = McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(context -> {
					admissions.incrementAndGet();
					return McpAdmissionDecision.accepted(ADMISSION_IDENTITY);
				})
				.host(LOOPBACK)
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.handlerInterceptor(handlerInterceptor)
				.toolOutputSanitizer(toolOutputSanitizer)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
		taskManager.ifPresent(builder::taskManager);
		return builder.build();
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

	private static McpTask task(@NonNull String taskId,
			@NonNull McpTaskOrigin taskOrigin,
			@NonNull McpTaskStatus taskStatus) {
		McpTask.Builder builder = McpTask.withTaskId(taskId, taskOrigin,
					taskStatus, CREATED_AT, LAST_UPDATED_AT)
				.taskStatusMessage(taskStatus.name().toLowerCase(Locale.ROOT)
						+ "-message")
				.timeToLive(TASK_TIME_TO_LIVE)
				.pollInterval(POLL_INTERVAL)
				.metadata(McpJsonObject.builder()
						.put("com.example/task-metadata",
								taskStatus.name().toLowerCase(Locale.ROOT))
						.build());
		switch (taskStatus) {
			case INPUT_REQUIRED -> builder.addInputRequest("approval",
					McpInputRequest.fromDeclaration(ROOTS_DECLARATION,
							McpJsonObject.emptyInstance()));
			case COMPLETED -> builder.completedResult(
					McpCompleteResult.fromToolText("completed-output")
							.withMetadata(McpJsonObject.builder()
									.put("com.example/completed", "nested")
									.build()));
			case FAILED -> builder.failure(McpJsonRpcError.fromApplication(41001,
					"task-failure", McpJsonObject.builder()
							.put("reason", "failure-detail").build()));
			case WORKING, CANCELED -> {
			}
		}
		return builder.build();
	}

	private static void assertTaskGet(int port, @NonNull String requestId,
			@NonNull McpTask task, @NonNull String detailedFields)
			throws Exception {
		HttpResponse<String> response = callTask(port, "tasks/get", requestId,
				task.getTaskId(), true);
		assertNoStore(response, 200);
		Assertions.assertEquals(taskResponse(requestId, task, false,
				detailedFields), response.body());
	}

	private static String taskResponse(@NonNull String requestId,
			@NonNull McpTask task, boolean creation,
			@NonNull String detailedFields) {
		String taskStatus = switch (task.getTaskStatus()) {
			case WORKING -> "working";
			case INPUT_REQUIRED -> "input_required";
			case COMPLETED -> "completed";
			case FAILED -> "failed";
			case CANCELED -> "cancelled";
		};
		String metadataStatus = task.getTaskStatus().name()
				.toLowerCase(Locale.ROOT);
		return "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"result\":{\"taskId\":\"" + task.getTaskId()
				+ "\",\"status\":\"" + taskStatus
				+ "\",\"statusMessage\":\"" + metadataStatus
				+ "-message\",\"createdAt\":\"" + CREATED_AT
				+ "\",\"lastUpdatedAt\":\"" + LAST_UPDATED_AT
				+ "\",\"ttlMs\":60000,\"pollIntervalMs\":250"
				+ detailedFields + ",\"resultType\":\""
				+ (creation ? "task" : "complete")
				+ "\",\"_meta\":{\"com.example/task-metadata\":\""
				+ metadataStatus + "\"}}}";
	}

	private static void assertIdentity(@NonNull McpRequestContext context) {
		McpAdmissionIdentity identity = context.getAdmissionIdentity();
		Assertions.assertEquals(ADMISSION_IDENTITY.getRateLimitPartitionKey(),
				identity.getRateLimitPartitionKey());
		Assertions.assertEquals(ADMISSION_IDENTITY.getAuthorizationPartitionKey(),
				identity.getAuthorizationPartitionKey());
		Assertions.assertSame(PRINCIPAL, identity.getPrincipal().orElseThrow());
		Assertions.assertSame(APPLICATION_CONTEXT,
				identity.getApplicationContext().orElseThrow());
	}

	private static HttpResponse<String> callTool(int port,
			@NonNull String requestId, @NonNull String taskId,
			boolean completeImmediately, boolean tasksCapable) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"tools/call\",\"params\":{"
				+ taskMetadata(tasksCapable) + ",\"name\":\"" + TOOL_NAME
				+ "\",\"arguments\":{\"taskId\":\"" + taskId
				+ "\",\"completeImmediately\":" + completeImmediately + "}}}";
		return post(port, "tools/call", TOOL_NAME, body);
	}

	private static HttpResponse<String> callTask(int port,
			@NonNull String method, @NonNull String requestId,
			@NonNull String taskId, boolean tasksCapable) throws Exception {
		return callTask(port, method, requestId, taskId, tasksCapable, true);
	}

	private static HttpResponse<String> callTask(int port,
			@NonNull String method, @NonNull String requestId,
			@NonNull String taskId, boolean tasksCapable, boolean rootsCapable)
			throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"" + method + "\",\"params\":{"
				+ taskMetadata(tasksCapable, rootsCapable)
				+ ",\"taskId\":\"" + taskId
				+ "\",\"com.example/future\":true}}";
		return post(port, method, taskId, body);
	}

	private static HttpResponse<String> callTaskUpdate(int port,
			@NonNull String requestId, @NonNull String taskId,
			boolean tasksCapable) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"tasks/update\",\"params\":{"
				+ taskMetadata(tasksCapable) + ",\"taskId\":\"" + taskId
				+ "\",\"inputResponses\":{\"approval\":{"
				+ "\"action\":\"decline\"}},\"com.example/future\":true}}";
		return post(port, "tasks/update", taskId, body);
	}

	private static HttpResponse<String> callObsoleteTaskMethod(int port,
			@NonNull String method) throws Exception {
		String requestId = method.replace('/', '-');
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"" + method + "\",\"params\":{"
				+ taskMetadata(true) + ",\"taskId\":\"obsolete-task\"}}";
		return post(port, method, null, body);
	}

	private static String taskMetadata(boolean tasksCapable) {
		return taskMetadata(tasksCapable, true);
	}

	private static String taskMetadata(boolean tasksCapable,
			boolean rootsCapable) {
		String capabilities = tasksCapable
				? "{\"extensions\":{\"" + TASKS_EXTENSION_ID
						+ "\":{}}" + (rootsCapable ? ",\"roots\":{}" : "")
						+ "}"
				: (rootsCapable ? "{\"roots\":{}}" : "{}");
		return "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":"
				+ capabilities + ",\"com.example/request\":\"retained\"}";
	}

	private static HttpResponse<String> post(int port, @NonNull String method,
			String operationName, @NonNull String body) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method);
		if (operationName != null)
			request.header("Mcp-Name", operationName);
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request.POST(HttpRequest.BodyPublishers.ofString(body,
						StandardCharsets.UTF_8)).build(),
						HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static void assertNoStore(@NonNull HttpResponse<String> response,
			int expectedStatus) {
		Assertions.assertEquals(expectedStatus, response.statusCode(),
				response.body());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
		Assertions.assertEquals("application/json",
				response.headers().firstValue("Content-Type").orElseThrow());
	}

	private static void assertInvalidParamsWithoutTaskDisclosure(
			@NonNull HttpResponse<String> response, @NonNull String taskId) {
		assertNoStore(response, 400);
		Assertions.assertTrue(response.body().contains("\"code\":-32602"),
				response.body());
		Assertions.assertTrue(response.body().contains(
				"\"message\":\"Invalid params\""), response.body());
		Assertions.assertFalse(response.body().contains(taskId), response.body());
	}

	private static void assertInternalErrorWithoutDisclosure(
			@NonNull HttpResponse<String> response, @NonNull String taskId,
			@NonNull String canary) {
		assertNoStore(response, 500);
		Assertions.assertTrue(response.body().contains("\"code\":-32603"),
				response.body());
		Assertions.assertTrue(response.body().contains(
				"\"message\":\"Internal error\""), response.body());
		Assertions.assertFalse(response.body().contains("\"data\""),
				response.body());
		Assertions.assertFalse(response.body().contains(taskId), response.body());
		Assertions.assertFalse(response.body().contains(canary), response.body());
	}

	private static void assertMethodNotFound(
			@NonNull HttpResponse<String> response) {
		assertNoStore(response, 404);
		Assertions.assertTrue(response.body().contains("\"code\":-32601"),
				response.body());
		Assertions.assertTrue(response.body().contains(
				"\"message\":\"Method not found\""), response.body());
	}

	private enum FindMode {
		NORMAL,
		EMPTY,
		UNAUTHORIZED,
		NULL_OPTIONAL,
		WRONG_ID,
		WRONG_ORIGIN,
		THROW
	}

	private enum MutationMode {
		SUCCESS,
		NOT_FOUND,
		THROW
	}

	private static final class ScriptedTaskManager implements McpTaskManager {
		@NonNull
		private final Map<@NonNull String, @NonNull McpTask> tasks =
				new ConcurrentHashMap<>();
		@NonNull
		private final AtomicInteger findInvocations = new AtomicInteger();
		@NonNull
		private final AtomicReference<McpTaskUpdateContext> updateContext =
				new AtomicReference<>();
		@NonNull
		private final AtomicReference<McpTaskRequestContext> cancelContext =
				new AtomicReference<>();
		@NonNull
		private volatile Optional<@NonNull McpTaskOrigin> taskOrigin =
				Optional.empty();
		@NonNull
		private volatile FindMode findMode = FindMode.NORMAL;
		@NonNull
		private volatile MutationMode mutationMode = MutationMode.SUCCESS;

		@Override
		@NonNull
		public Optional<@NonNull McpTask> findTask(
				@NonNull McpTaskRequestContext context) throws Exception {
			this.findInvocations.incrementAndGet();
			return switch (this.findMode) {
				case NORMAL -> Optional.ofNullable(
						this.tasks.get(context.getTaskId()));
				case EMPTY, UNAUTHORIZED -> Optional.empty();
				case NULL_OPTIONAL -> null;
				case WRONG_ID -> Optional.of(task("wrong-manager-task-id",
						this.taskOrigin.orElseThrow(), McpTaskStatus.WORKING));
				case WRONG_ORIGIN -> Optional.of(task(context.getTaskId(),
						McpTaskOrigin.fromPersistedState(McpJsonObject.builder()
								.put("formatVersion", 1)
								.put("canary", "wrong-origin-secret")
								.build()), McpTaskStatus.WORKING));
				case THROW -> throw new Exception(FIND_FAILURE_CANARY);
			};
		}

		@Override
		public void updateTask(@NonNull McpTaskUpdateContext context)
				throws Exception {
			this.updateContext.set(context);
			applyMutationMode();
		}

		@Override
		public void requestTaskCancelation(
				@NonNull McpTaskRequestContext context) throws Exception {
			this.cancelContext.set(context);
			applyMutationMode();
		}

		private void applyMutationMode() throws Exception {
			switch (this.mutationMode) {
				case SUCCESS -> {
				}
				case NOT_FOUND -> throw new McpTaskNotFoundException();
				case THROW -> throw new Exception(MUTATION_FAILURE_CANARY);
			}
		}

		private void putTask(@NonNull McpTask task) {
			this.tasks.put(task.getTaskId(), task);
		}

		@NonNull
		private McpTask getTask(@NonNull String taskId) {
			return Optional.ofNullable(this.tasks.get(taskId)).orElseThrow();
		}
	}

	private record RequiredArguments(@NonNull String required) {
	}
}
