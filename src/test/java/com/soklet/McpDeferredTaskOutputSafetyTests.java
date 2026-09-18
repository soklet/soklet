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

import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
	private static final String RESULT_METADATA_CANARY =
			"DEFERRED-RESULT-METADATA-MUST-NOT-LEAK";
	private static final String POLICY_FAILURE_CANARY =
			"CATALOG-POLICY-FAILURE-MUST-NOT-LEAK";
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
					SanitizerMode.SCHEMA_MISMATCH,
					SanitizerMode.OMIT_SUCCESS, SanitizerMode.NULL,
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
			Assertions.assertEquals(9, sanitizerInvocations.get());
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

	@Test
	public void completedTaskResultIsStatusOnlyWhenOriginToolAccessIsRevoked()
			throws Exception {
		AtomicReference<CatalogPolicyMode> policyMode =
				new AtomicReference<>(CatalogPolicyMode.ALLOW);
		AtomicInteger policyInvocations = new AtomicInteger();
		AtomicReference<SanitizerMode> sanitizerMode =
				new AtomicReference<>(SanitizerMode.VALID);
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		TaskManager taskManager = new TaskManager();
		McpToolRegistration<TaskArguments> tool = tool(taskManager,
				new CopyOnWriteArrayList<>());
		McpServer server = server(tool, taskManager,
				McpHandlerInterceptor.passThroughInstance(),
				sanitizer(sanitizerMode, sanitizerInvocations),
				catalogAccessPolicy(tool, policyMode, policyInvocations));
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);
			assertSuccess(createTask(port));
			Assertions.assertEquals(1, policyInvocations.get());
			Assertions.assertEquals(0, sanitizerInvocations.get());

			policyMode.set(CatalogPolicyMode.DENY);
			HttpResponse<String> response = getTask(port, "revoked-result");
			assertSuccess(response);
			assertContains(response.body(), "\"taskId\":\"" + TASK_ID + "\"");
			assertContains(response.body(), "\"status\":\"completed\"");
			Assertions.assertEquals(1,
					occurrences(response.body(), "\"result\":"), response.body());
			for (String canary : List.of(ORIGINAL_CANARY,
					RESULT_METADATA_CANARY,
					"\"structuredContent\""))
				Assertions.assertFalse(response.body().contains(canary),
						response.body());
			Assertions.assertEquals(2, policyInvocations.get(),
					"Task retrieval must reauthorize the origin tool once.");
			Assertions.assertEquals(0, sanitizerInvocations.get(),
					"A denied origin tool must suppress output before sanitization.");
		} finally {
			soklet.close();
		}
	}

	@Test
	public void completedTaskReauthorizationFailuresAreFixedAndBypassSanitizer()
			throws Exception {
		AtomicReference<CatalogPolicyMode> policyMode =
				new AtomicReference<>(CatalogPolicyMode.ALLOW);
		AtomicInteger policyInvocations = new AtomicInteger();
		AtomicReference<SanitizerMode> sanitizerMode =
				new AtomicReference<>(SanitizerMode.VALID);
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		TaskManager taskManager = new TaskManager();
		McpToolRegistration<TaskArguments> tool = tool(taskManager,
				new CopyOnWriteArrayList<>());
		McpServer server = server(tool, taskManager,
				McpHandlerInterceptor.passThroughInstance(),
				sanitizer(sanitizerMode, sanitizerInvocations),
				catalogAccessPolicy(tool, policyMode, policyInvocations));
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);
			assertSuccess(createTask(port));

			policyMode.set(CatalogPolicyMode.RETURN_NULL);
			assertFixedInternalError(getTask(port, "null-policy"),
					"null-policy");
			policyMode.set(CatalogPolicyMode.THROW);
			assertFixedInternalError(getTask(port, "throwing-policy"),
					"throwing-policy");

			Assertions.assertEquals(3, policyInvocations.get(),
					"Creation and both retrievals must each evaluate policy once.");
			Assertions.assertEquals(0, sanitizerInvocations.get(),
					"A failed policy check must precede deferred-output sanitization.");
		} finally {
			soklet.close();
		}
	}

	@Test
	public void completedTaskReauthorizationDeadlineCancelsItsPolicyToken()
			throws Exception {
		AtomicReference<SanitizerMode> sanitizerMode =
				new AtomicReference<>(SanitizerMode.VALID);
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		TaskManager taskManager = new TaskManager();
		McpToolRegistration<TaskArguments> tool = tool(taskManager,
				new CopyOnWriteArrayList<>());
		BlockingPolicyProbe policyProbe = new BlockingPolicyProbe(tool, true);
		McpServer server = serverWithRequestTimeout(tool, taskManager,
				McpHandlerInterceptor.passThroughInstance(),
				sanitizer(sanitizerMode, sanitizerInvocations),
				policyProbe.policy(), Duration.ofSeconds(1));
		Soklet soklet = managedSoklet(server);
		ExecutorService clientExecutor = Executors.newSingleThreadExecutor();
		Future<HttpResponse<String>> retrieval = null;

		try {
			soklet.start();
			int port = boundPort(server);
			assertSuccess(createTask(port));
			policyProbe.block();
			retrieval = clientExecutor.submit(() ->
					getTask(port, "reauthorization-deadline"));
			policyProbe.awaitEntered();

			HttpResponse<String> response = retrieval.get(5, TimeUnit.SECONDS);
			assertActiveDeadline(response, "reauthorization-deadline");
			policyProbe.awaitCanceled();
			policyProbe.assertCanceledWith(
					StreamTerminationReason.RESPONSE_TIMEOUT);
			policyProbe.awaitExited();
			awaitNoActiveHandlerExecutions(server);
			Assertions.assertEquals(2, policyProbe.invocations());
			Assertions.assertEquals(0, sanitizerInvocations.get(),
					"A timed-out task reauthorization must bypass sanitization.");
		} finally {
			policyProbe.release();
			if (retrieval != null)
				retrieval.cancel(true);
			clientExecutor.shutdownNow();
			clientExecutor.awaitTermination(5, TimeUnit.SECONDS);
			soklet.close();
		}
	}

	@Test
	public void completedTaskReauthorizationHardStopCancelsItsPolicyToken()
			throws Exception {
		AtomicReference<SanitizerMode> sanitizerMode =
				new AtomicReference<>(SanitizerMode.VALID);
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		TaskManager taskManager = new TaskManager();
		McpToolRegistration<TaskArguments> tool = tool(taskManager,
				new CopyOnWriteArrayList<>());
		BlockingPolicyProbe policyProbe = new BlockingPolicyProbe(tool, false);
		McpServer server = serverWithRequestTimeout(tool, taskManager,
				McpHandlerInterceptor.passThroughInstance(),
				sanitizer(sanitizerMode, sanitizerInvocations),
				policyProbe.policy(), Duration.ofSeconds(30));
		Soklet soklet = managedSoklet(server);
		ExecutorService clientExecutor = Executors.newSingleThreadExecutor();
		Future<HttpResponse<String>> retrieval = null;

		try {
			soklet.start();
			int port = boundPort(server);
			assertSuccess(createTask(port));
			policyProbe.block();
			retrieval = clientExecutor.submit(() ->
					getTask(port, "reauthorization-stop"));
			policyProbe.awaitEntered();

			runtimeBridge(server).forceLifecycle();
			policyProbe.awaitCanceled();
			policyProbe.assertCanceledWith(
					StreamTerminationReason.SERVER_STOPPING);
			policyProbe.awaitExited();
			Assertions.assertEquals(2, policyProbe.invocations());
			Assertions.assertEquals(0, sanitizerInvocations.get(),
					"A stopped task reauthorization must bypass sanitization.");
		} finally {
			policyProbe.release();
			if (retrieval != null)
				retrieval.cancel(true);
			clientExecutor.shutdownNow();
			clientExecutor.awaitTermination(5, TimeUnit.SECONDS);
			soklet.close();
		}
	}

	@Test
	public void completedTaskIsStatusOnlyWhenItsOriginToolIsNoLongerRegistered()
			throws Exception {
		AtomicReference<SanitizerMode> sanitizerMode =
				new AtomicReference<>(SanitizerMode.VALID);
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		TaskManager taskManager = new TaskManager();
		McpToolRegistration<TaskArguments> originTool = tool(taskManager,
				new CopyOnWriteArrayList<>());
		McpToolOutputSanitizer sanitizer = sanitizer(sanitizerMode,
				sanitizerInvocations);
		McpServer creatorServer = server(originTool, taskManager,
				McpHandlerInterceptor.passThroughInstance(), sanitizer);
		Soklet creator = managedSoklet(creatorServer);

		try {
			creator.start();
			assertSuccess(createTask(boundPort(creatorServer)));
		} finally {
			creator.close();
		}

		AtomicReference<CatalogPolicyMode> policyMode =
				new AtomicReference<>(CatalogPolicyMode.ALLOW);
		AtomicInteger policyInvocations = new AtomicInteger();
		McpToolRegistration<TaskArguments> replacementTool =
				McpToolRegistration.withName("tasks.replacement")
						.argumentAndOutputTypes(TaskArguments.class,
								TaskOutput.class)
						.operationHandler((request, arguments, features) -> {
							throw new AssertionError(
									"The replacement tool must not be invoked.");
						})
						.build();
		McpServer readerServer = server(replacementTool, taskManager,
				McpHandlerInterceptor.passThroughInstance(), sanitizer,
				catalogAccessPolicy(originTool, policyMode, policyInvocations));
		Soklet reader = managedSoklet(readerServer);

		try {
			reader.start();
			HttpResponse<String> response = getTask(boundPort(readerServer),
					"removed-origin-tool");
			assertSuccess(response);
			assertContains(response.body(), "\"taskId\":\"" + TASK_ID + "\"");
			assertContains(response.body(), "\"status\":\"completed\"");
			Assertions.assertEquals(1,
					occurrences(response.body(), "\"result\":"), response.body());
			for (String canary : List.of(ORIGINAL_CANARY,
					RESULT_METADATA_CANARY,
					"\"structuredContent\""))
				Assertions.assertFalse(response.body().contains(canary),
						response.body());
			Assertions.assertEquals(0, policyInvocations.get(),
					"A missing current registration must short-circuit policy.");
			Assertions.assertEquals(0, sanitizerInvocations.get(),
					"A missing current registration must suppress output before sanitization.");
		} finally {
			reader.close();
		}
	}

	@Test
	public void nonCompletedTaskReadsDoNotInitializePolicyOrLocalization()
			throws Exception {
		for (McpTaskStatus status : List.of(McpTaskStatus.WORKING,
				McpTaskStatus.FAILED)) {
			for (LocalizationProviderMode providerMode : List.of(
					LocalizationProviderMode.RETURN_NULL,
					LocalizationProviderMode.THROW)) {
				AtomicReference<CatalogPolicyMode> policyMode =
						new AtomicReference<>(CatalogPolicyMode.ALLOW);
				AtomicInteger policyInvocations = new AtomicInteger();
				AtomicReference<SanitizerMode> sanitizerMode =
						new AtomicReference<>(SanitizerMode.VALID);
				AtomicInteger sanitizerInvocations = new AtomicInteger();
				AtomicReference<LocalizationProviderMode> localizationMode =
						new AtomicReference<>(LocalizationProviderMode.VALID);
				AtomicInteger localizationProviderInvocations =
						new AtomicInteger();
				TaskManager taskManager = new TaskManager();
				McpToolRegistration<TaskArguments> tool = tool(taskManager,
						new CopyOnWriteArrayList<>());
				McpServer server = server(tool, taskManager,
						McpHandlerInterceptor.passThroughInstance(),
						sanitizer(sanitizerMode, sanitizerInvocations),
						catalogAccessPolicy(tool, policyMode,
								policyInvocations),
						localizer(localizationMode,
								localizationProviderInvocations));
				Soklet soklet = managedSoklet(server);

				try {
					soklet.start();
					int port = boundPort(server);
					assertSuccess(createTask(port));
					Assertions.assertEquals(1, policyInvocations.get());
					Assertions.assertEquals(1,
							localizationProviderInvocations.get());
					Assertions.assertEquals(0, sanitizerInvocations.get());

					McpTaskOrigin origin = taskManager.taskOrigin.orElseThrow();
					McpTask.Builder taskBuilder = McpTask.withTaskId(TASK_ID,
							origin, status, NOW, NOW);
					if (status == McpTaskStatus.FAILED)
						taskBuilder.failure(McpJsonRpcError.fromApplication(41_001,
								"task-failure"));
					taskManager.task = Optional.of(taskBuilder.build());
					taskManager.findInvocations.set(0);
					policyInvocations.set(0);
					localizationProviderInvocations.set(0);
					localizationMode.set(providerMode);

					String requestId = status.name().toLowerCase() + "-"
							+ providerMode.name().toLowerCase();
					HttpResponse<String> response = getTask(port, requestId);
					assertSuccess(response);
					assertContains(response.body(), "\"taskId\":\"" + TASK_ID
							+ "\"");
					assertContains(response.body(), "\"status\":\""
							+ status.name().toLowerCase() + "\"");
					Assertions.assertEquals(1, taskManager.findInvocations.get(),
							"Task ownership must remain manager-governed for "
									+ requestId + ".");
					Assertions.assertEquals(0, policyInvocations.get(),
							"A non-completed task must not evaluate catalog policy for "
									+ requestId + ".");
					Assertions.assertEquals(0,
							localizationProviderInvocations.get(),
							"A non-completed task must not create localization context for "
									+ requestId + ".");
					Assertions.assertEquals(0, sanitizerInvocations.get(),
							"A non-completed task must not invoke the sanitizer for "
									+ requestId + ".");
				} finally {
					soklet.close();
				}
			}
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
											.build())
									.withMetadata(McpJsonObject.builder()
											.put("deferredResultMetadata",
													RESULT_METADATA_CANARY)
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
				case OMIT_SUCCESS -> McpToolOutput.fromText(
						"DEFERRED-OMITTED-STRUCTURED-CONTENT");
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
		return server(tool, taskManager, interceptor, sanitizer, null);
	}

	private static McpServer server(@NonNull McpToolRegistration<?> tool,
			@NonNull McpTaskManager taskManager,
			@NonNull McpHandlerInterceptor interceptor,
			@NonNull McpToolOutputSanitizer sanitizer,
			@Nullable McpCatalogAccessPolicy catalogAccessPolicy) {
		return server(tool, taskManager, interceptor, sanitizer,
				catalogAccessPolicy, null);
	}

	private static McpServer server(@NonNull McpToolRegistration<?> tool,
			@NonNull McpTaskManager taskManager,
			@NonNull McpHandlerInterceptor interceptor,
			@NonNull McpToolOutputSanitizer sanitizer,
			@Nullable McpCatalogAccessPolicy catalogAccessPolicy,
			@Nullable McpLocalizer localizer) {
		return server(tool, taskManager, interceptor, sanitizer,
				catalogAccessPolicy, localizer, Duration.ofSeconds(60));
	}

	private static McpServer serverWithRequestTimeout(
			@NonNull McpToolRegistration<?> tool,
			@NonNull McpTaskManager taskManager,
			@NonNull McpHandlerInterceptor interceptor,
			@NonNull McpToolOutputSanitizer sanitizer,
			@NonNull McpCatalogAccessPolicy catalogAccessPolicy,
			@NonNull Duration requestTimeout) {
		return server(tool, taskManager, interceptor, sanitizer,
				catalogAccessPolicy, null, requestTimeout);
	}

	private static McpServer server(@NonNull McpToolRegistration<?> tool,
			@NonNull McpTaskManager taskManager,
			@NonNull McpHandlerInterceptor interceptor,
			@NonNull McpToolOutputSanitizer sanitizer,
			@Nullable McpCatalogAccessPolicy catalogAccessPolicy,
			@Nullable McpLocalizer localizer,
			@NonNull Duration requestTimeout) {
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion(
						"deferred-task-output-safety-test", "4.0.0").build())
				.addTool(tool)
				.build();
		return McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.host(LOOPBACK)
				.requestTimeout(requestTimeout)
				.catalogAccessPolicy(catalogAccessPolicy)
				.localizer(localizer)
				.taskManager(taskManager)
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.handlerInterceptor(interceptor)
				.toolOutputSanitizer(sanitizer)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
	}

	private static McpLocalizer localizer(
			@NonNull AtomicReference<LocalizationProviderMode> mode,
			@NonNull AtomicInteger invocations) {
		return McpLocalizer.withFallbackLocale(Locale.ENGLISH, request -> {
			invocations.incrementAndGet();
			return switch (mode.get()) {
				case VALID -> McpLocalizationContext.withLocale(Locale.ENGLISH,
						text -> McpLocalizationResult.useDefaultText()).build();
				case RETURN_NULL -> null;
				case THROW -> throw new IllegalStateException(
						"LOCALIZATION-PROVIDER-FAILURE-MUST-NOT-LEAK");
			};
		}).build();
	}

	private static McpCatalogAccessPolicy catalogAccessPolicy(
			@NonNull McpToolRegistration<?> expectedTool,
			@NonNull AtomicReference<CatalogPolicyMode> mode,
			@NonNull AtomicInteger invocations) {
		return McpCatalogAccessPolicy.fromEvaluators(
				(context, registration, features) -> {
					Assertions.assertSame(expectedTool, registration);
					invocations.incrementAndGet();
					return switch (mode.get()) {
						case ALLOW -> true;
						case DENY -> false;
						case RETURN_NULL -> null;
						case THROW -> throw new IllegalStateException(
								POLICY_FAILURE_CANARY);
					};
				}, (context, registration, features) -> {
					throw new AssertionError(
							"Task-result reauthorization must not evaluate prompts.");
				});
	}

	private static Soklet managedSoklet(@NonNull McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
					.build());
	}

	@NonNull
	private static McpServerRuntimeBridge runtimeBridge(
			@NonNull McpServer server) throws ReflectiveOperationException {
		Field field = DefaultMcpServer.class.getDeclaredField("runtimeBridge");
		field.setAccessible(true);
		return (McpServerRuntimeBridge) field.get(server);
	}

	private static int boundPort(@NonNull McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	private static void awaitNoActiveHandlerExecutions(
			@NonNull McpServer server) {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		McpServerDiagnostics diagnostics;
		do {
			diagnostics = server.getDiagnostics();
			if (diagnostics.getActiveHandlerExecutions() == 0)
				return;
			Thread.onSpinWait();
		} while (System.nanoTime() - deadline < 0L);
		Assertions.fail("Timed out waiting for task-result reauthorization "
				+ "to leave bounded application execution; latest=" + diagnostics);
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

	private static void assertActiveDeadline(
			@NonNull HttpResponse<String> response, @NonNull String requestId) {
		Assertions.assertEquals(504, response.statusCode(), response.body());
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

	private static int occurrences(@NonNull String value,
			@NonNull String substring) {
		int count = 0;
		int offset = 0;
		while ((offset = value.indexOf(substring, offset)) >= 0) {
			count++;
			offset += substring.length();
		}
		return count;
	}

	private enum CatalogPolicyMode {
		ALLOW,
		DENY,
		RETURN_NULL,
		THROW
	}

	private enum SanitizerMode {
		VALID,
		ERROR,
		PASS_THROUGH,
		SCHEMA_MISMATCH,
		OMIT_SUCCESS,
		NULL,
		THROW,
		DEPTH_LIMIT,
		NODE_LIMIT,
		SIZE_LIMIT
	}

	private enum LocalizationProviderMode {
		VALID,
		RETURN_NULL,
		THROW
	}

	private static final class BlockingPolicyProbe {
		private final McpToolRegistration<?> expectedTool;
		private final boolean returnTrueAfterCancelation;
		private final AtomicBoolean blocking;
		private final AtomicInteger invocations;
		private final AtomicInteger cancelationCallbacks;
		private final AtomicReference<CancelationToken> token;
		private final AtomicReference<StreamTerminationReason> reason;
		private final AtomicReference<Optional<Throwable>> cause;
		private final CountDownLatch entered;
		private final CountDownLatch canceled;
		private final CountDownLatch release;
		private final CountDownLatch exited;

		private BlockingPolicyProbe(
				@NonNull McpToolRegistration<?> expectedTool,
				boolean returnTrueAfterCancelation) {
			this.expectedTool = expectedTool;
			this.returnTrueAfterCancelation = returnTrueAfterCancelation;
			this.blocking = new AtomicBoolean();
			this.invocations = new AtomicInteger();
			this.cancelationCallbacks = new AtomicInteger();
			this.token = new AtomicReference<>();
			this.reason = new AtomicReference<>();
			this.cause = new AtomicReference<>();
			this.entered = new CountDownLatch(1);
			this.canceled = new CountDownLatch(1);
			this.release = new CountDownLatch(1);
			this.exited = new CountDownLatch(1);
		}

		@NonNull
		private McpCatalogAccessPolicy policy() {
			return McpCatalogAccessPolicy.fromEvaluators(
					(context, registration, features) -> {
						Assertions.assertSame(this.expectedTool, registration);
						this.invocations.incrementAndGet();
						if (!this.blocking.get())
							return true;

						CancelationToken observed = features.getCancelationToken();
						this.token.set(observed);
						observed.onCancel(() -> {
							this.cancelationCallbacks.incrementAndGet();
							this.reason.set(observed.getCancelationReason()
									.orElse(null));
							this.cause.set(observed.getCancelationCause());
							this.canceled.countDown();
							this.release.countDown();
						});
						this.entered.countDown();
						try {
							while (this.release.getCount() != 0L) {
								try {
									this.release.await();
								} catch (InterruptedException ignored) {
									// The public token, not interruption alone, releases
									// this task-result authorization check.
								}
							}
							if (this.returnTrueAfterCancelation)
								return true;
							throw new InterruptedException(
									"Task-result reauthorization was canceled.");
						} finally {
							this.exited.countDown();
						}
					}, (context, registration, features) -> {
						throw new AssertionError(
								"Task-result reauthorization must not evaluate prompts.");
					});
		}

		private void block() {
			this.blocking.set(true);
		}

		private void awaitEntered() throws InterruptedException {
			Assertions.assertTrue(this.entered.await(5, TimeUnit.SECONDS),
					"Task-result reauthorization did not enter.");
		}

		private void awaitCanceled() throws InterruptedException {
			Assertions.assertTrue(this.canceled.await(5, TimeUnit.SECONDS),
					"The task-result policy token was not canceled.");
		}

		private void awaitExited() throws InterruptedException {
			Assertions.assertTrue(this.exited.await(5, TimeUnit.SECONDS),
					"The canceled task-result reauthorization did not exit.");
		}

		private void assertCanceledWith(
				@NonNull StreamTerminationReason expectedReason) {
			CancelationToken observed = this.token.get();
			Assertions.assertNotNull(observed);
			Assertions.assertTrue(observed.isCanceled());
			Assertions.assertEquals(expectedReason, this.reason.get());
			Assertions.assertEquals(Optional.empty(), this.cause.get());
			Assertions.assertEquals(Optional.of(expectedReason),
					observed.getCancelationReason());
			Assertions.assertTrue(observed.getCancelationCause().isEmpty());
			Assertions.assertEquals(1, this.cancelationCallbacks.get());
		}

		private int invocations() {
			return this.invocations.get();
		}

		private void release() {
			this.release.countDown();
		}
	}

	private static final class TaskManager implements McpTaskManager {
		@NonNull
		private volatile Optional<@NonNull McpTaskOrigin> taskOrigin =
				Optional.empty();
		@NonNull
		private volatile Optional<@NonNull McpTask> task = Optional.empty();
		@NonNull
		private final AtomicInteger findInvocations = new AtomicInteger();

		@Override
		@NonNull
		public Optional<@NonNull McpTask> findTask(
				@NonNull McpTaskRequestContext context) {
			this.findInvocations.incrementAndGet();
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
