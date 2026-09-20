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
import com.soklet.internal.mcp.protocol.McpPublicJsonValueConverter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Complete-result sanitization boundaries, including independently admitted
 * reads of unchanged task snapshots restored from persisted origin state.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpCompleteToolResultSanitizerRuntimeTests {
	private static final String PATH = "/mcp";
	private static final String TOOL = "result.sanitized";
	private static final String TASK = "retained-result-task";
	private static final String META = "com.example/viewData";
	private static final String SECRET = "PRIVATE-ORIGINAL-RESULT";
	private static final String PARTIAL = "PRIVATE-PARTIAL-RESULT";
	private static final Duration WAIT = Duration.ofSeconds(2);
	private static final McpJsonCodec JSON = new McpJsonCodec(McpJsonLimits.productionDefaults());
	private static final LifecyclePolicy TEST_LIFECYCLE_POLICY = LifecyclePolicy.builder()
			.startupTimeout(Duration.ofSeconds(5))
			.startupCancelationTimeout(Duration.ofSeconds(2))
			.gracefulShutdownTimeout(Duration.ofSeconds(2))
			.forcedShutdownTimeout(Duration.ofSeconds(1)).build();

	@Test
	void sanitizerReceivesInterceptedCompleteResultAndReplacesBothPayloadAndMetadata() {
		List<String> stages = new ArrayList<>();
		McpCompleteResult original = complete(SECRET, metadata(SECRET));
		McpCompleteResult intercepted = original.toBuilder().metadata(metadata("intercepted-" + SECRET)).build();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName(TOOL).jsonObjectArguments()
				.handler((request, arguments, features) -> {
					stages.add("handler");
					return original;
				}).build();
		McpHandlerInterceptor interceptor = (request, features, continuation) -> {
			assertSame(original, continuation.proceed());
			stages.add("interceptor");
			return intercepted;
		};
		McpToolResultSanitizer sanitizer = (request, toolName, rawArguments, result) -> {
			stages.add("sanitizer");
			assertEquals(TOOL, toolName);
			assertEquals(arguments("replace"), rawArguments);
			assertEquals("alice", request.getAdmissionIdentity().getPrincipal().orElseThrow());
			assertEquals(McpOperationType.TOOLS_CALL, request.getOperationType());
			assertEquals(intercepted, result);
			return result.toBuilder().payload(McpToolOutput.fromText("safe text"))
					.metadata(metadata("safe metadata")).build();
		};
		run(List.of(tool), sanitizer, null, interceptor, simulator -> {
			Capture response = call(simulator, "replace", "alice", false);
			assertEquals(200, response.status(), response.body());
			assertEquals(metadata("safe metadata"), object(result(response).find("_meta").orElseThrow()));
			assertEquals("safe text", contentText(result(response)));
			assertFalse(response.body().contains(SECRET), response.body());
			assertEquals(List.of("handler", "interceptor", "sanitizer"), stages);
		});
		assertEquals(metadata(SECRET), original.getMetadata());
		assertEquals(metadata("intercepted-" + SECRET), intercepted.getMetadata());
	}

	@Test
	void metadataIsNeverPromotedIntoTextOrStructuredFallbackForNonAppsClients() {
		McpCompleteResult original = complete("authored ordinary answer", metadata(SECRET));
		run(List.of(jsonTool(original)), McpToolResultSanitizer.nonSanitizingInstance(), null,
				McpHandlerInterceptor.passThroughInstance(), simulator -> {
			for (int index = 0; index < 2; index++) {
				Capture response = call(simulator, "fallback", "alice", index == 1);
				assertEquals(200, response.status(), response.body());
				McpJsonObject payload = result(response);
				assertEquals(metadata(SECRET), object(payload.find("_meta").orElseThrow()));
				assertEquals("authored ordinary answer", contentText(payload));
				assertTrue(payload.find("structuredContent").isEmpty());
				assertEquals(1, ((McpJsonArray) payload.find("content").orElseThrow()).getElements().size());
			}
		});
	}

	@Test
	void sanitizedPayloadAndMetadataAreTheExclusiveSourceForAllOutputBudgets() {
		McpJsonObject deep = deeplyNestedMetadata(SECRET);
		McpCompleteResult original = McpCompleteResult.withToolOutput(McpToolOutput.fromStructuredContent(deep))
				.metadata(deep).build();
		McpJsonObject oversizedNodes = metadata(McpJsonArray.fromElements(Collections.nCopies(
				McpJsonLimits.productionDefaults().maximumNodeCount(), McpJsonString.fromValue(PARTIAL))));
		McpJsonObject oversizedBytes = McpJsonObject.builder()
				.put("com.example/bytes", McpJsonArray.fromElements(Collections.nCopies(5,
						McpJsonString.fromValue(PARTIAL + "x".repeat(900_000))))).build();
		McpToolResultSanitizer sanitizer = (request, toolName, rawArguments, result) -> {
			assertSame(original, result);
			McpJsonObject sanitizedMetadata = switch (mode(rawArguments)) {
				case "remove" -> McpJsonObject.emptyInstance();
				case "nodes" -> oversizedNodes;
				case "bytes" -> oversizedBytes;
				case "depth" -> deeplyNestedMetadata(PARTIAL);
				default -> throw new AssertionError("Unexpected test mode.");
			};
			return result.toBuilder().payload(McpToolOutput.fromText("safe answer"))
					.metadata(sanitizedMetadata).build();
		};
		run(List.of(jsonTool(original)), sanitizer, null, McpHandlerInterceptor.passThroughInstance(), simulator -> {
			Capture removed = call(simulator, "remove", "alice", false);
			assertEquals(200, removed.status(), removed.body());
			assertTrue(result(removed).find("_meta").isEmpty());
			assertEquals("safe answer", contentText(result(removed)));
			List<String> invalidModes = List.of("nodes", "bytes", "depth");
			for (int index = 0; index < 3; index++)
				assertFixedFailure(call(simulator, invalidModes.get(index), "alice", false));
			assertEquals(200, call(simulator, "remove", "alice", false).status());
		});
		assertSame(deep, original.getMetadata());
	}

	@Test
	void schemaValidationAndStructuredMirroringUseOnlyTheSanitizedCompleteResult() {
		McpCompleteResult original = McpCompleteResult.withToolOutput(McpToolOutput.fromStructuredContent(
				McpJsonObject.builder().put("invalidOriginal", SECRET).build())).metadata(metadata(SECRET)).build();
		McpToolRegistration<ModeArguments> tool = McpToolRegistration.withName(TOOL)
				.argumentAndOutputTypes(ModeArguments.class, SafeOutput.class)
				.inlineOperationHandler((request, arguments, features) -> original).build();
		McpToolResultSanitizer sanitizer = (request, toolName, rawArguments, result) -> {
			McpJsonObject structured = mode(rawArguments).equals("valid")
					? McpJsonObject.builder().put("value", "safe structured").build()
					: McpJsonObject.builder().put("invalidSanitized", PARTIAL).build();
			return result.toBuilder().payload(McpToolOutput.fromStructuredContent(structured))
					.metadata(metadata("out-of-band-only")).build();
		};
		run(List.of(tool), sanitizer, null, McpHandlerInterceptor.passThroughInstance(), simulator -> {
			Capture valid = call(simulator, "valid", "alice", false);
			assertEquals(200, valid.status(), valid.body());
			assertEquals(McpJsonObject.builder().put("value", "safe structured").build(),
					result(valid).find("structuredContent").orElseThrow());
			assertEquals("{\"value\":\"safe structured\"}", contentText(result(valid)));
			assertEquals(metadata("out-of-band-only"), result(valid).find("_meta").orElseThrow());
			assertFalse(valid.body().contains(SECRET), valid.body());
			assertFixedFailure(call(simulator, "invalid", "alice", false));
		});
	}

	@Test
	void wrongPayloadNullAndExceptionResultsFailClosedAndDoNotExposePartialMetadata() {
		McpCompleteResult original = complete(SECRET, metadata(SECRET));
		McpToolResultSanitizer sanitizer = (request, toolName, rawArguments, result) -> invalidResult(mode(rawArguments));
		run(List.of(jsonTool(original)), sanitizer, null, McpHandlerInterceptor.passThroughInstance(), simulator -> {
			List<String> invalidModes = List.of("prompt", "resource", "null", "exception");
			for (int index = 0; index < 4; index++)
				assertFixedFailure(call(simulator, invalidModes.get(index), "alice", false));
		});
	}

	@Test
	void sanitizerFailureDiagnosticsDiscardApplicationExceptionCauseAndSuppressedData() throws InterruptedException {
		String nestedCanary = "PRIVATE-NESTED-SANITIZER-EXCEPTION";
		String suppressedCanary = "PRIVATE-SUPPRESSED-SANITIZER-EXCEPTION";
		IllegalStateException applicationFailure = new IllegalStateException(PARTIAL,
				new IllegalArgumentException(nestedCanary));
		applicationFailure.addSuppressed(new IllegalStateException(suppressedCanary));
		List<Throwable> observedFailures = new CopyOnWriteArrayList<>();
		List<LogEvent> logs = new CopyOnWriteArrayList<>();
		CountDownLatch finished = new CountDownLatch(1);
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didFinishMcpRequestHandling(@NonNull McpRequestContext context,
					@NonNull McpRequestOutcome outcome, @Nullable McpJsonRpcError error,
					@NonNull Duration duration, @NonNull List<@NonNull Throwable> throwables) {
				observedFailures.addAll(throwables);
				finished.countDown();
			}

			@Override
			public void didReceiveLogEvent(@NonNull LogEvent event) {
				logs.add(event);
			}
		};
		run(List.of(jsonTool(complete(SECRET, metadata(SECRET)))),
				(request, toolName, rawArguments, result) -> { throw applicationFailure; }, null,
				McpHandlerInterceptor.passThroughInstance(), observer,
				simulator -> assertFixedFailure(call(simulator, "exception", "alice", false)));
		assertTrue(finished.await(5, TimeUnit.SECONDS), "The observed sanitizer request did not finish.");
		assertEquals(1, observedFailures.size());
		Throwable recorded = observedFailures.get(0);
		assertInstanceOf(IllegalStateException.class, recorded);
		assertNotSame(applicationFailure, recorded);
		assertEquals("The MCP tool-result sanitizer failed.", recorded.getMessage());
		assertNull(recorded.getCause());
		assertEquals(0, recorded.getSuppressed().length);
		StringWriter diagnostics = new StringWriter();
		PrintWriter writer = new PrintWriter(diagnostics);
		recorded.printStackTrace(writer);
		for (LogEvent event : logs) {
			writer.println(event.getMessage());
			event.getThrowable().ifPresent(throwable -> throwable.printStackTrace(writer));
		}
		writer.flush();
		for (String canary : List.of(SECRET, PARTIAL, nestedCanary, suppressedCanary))
			assertFalse(diagnostics.toString().contains(canary), "Framework diagnostics retained private result data.");
	}

	@Test
	void handlerMustAlreadyReturnToolPayloadBeforeSanitizerIsEntered() {
		AtomicInteger invocations = new AtomicInteger();
		McpCompleteResult wrong = McpCompleteResult.withPromptOutput(McpPromptOutput.fromMessages())
				.metadata(metadata(SECRET)).build();
		run(List.of(jsonTool(wrong)), (request, toolName, rawArguments, result) -> {
			invocations.incrementAndGet();
			return complete("should not repair handler type", McpJsonObject.emptyInstance());
		}, null, McpHandlerInterceptor.passThroughInstance(), simulator -> {
			assertFixedFailure(call(simulator, "wrong-handler", "alice", false));
			assertEquals(0, invocations.get());
		});
	}

	@Test
	void repeatedCrossIdentityTaskReadsUsePersistedOriginAndLeaveStoredResultsUnchanged() {
		StoredTaskManager manager = new StoredTaskManager();
		McpCompleteResult original = McpCompleteResult.withToolOutput(McpToolOutput.fromStructuredContent(
				McpJsonObject.builder().put("value", SECRET).build())).metadata(metadata(SECRET)).build();
		AtomicInteger sanitizerCalls = new AtomicInteger();
		McpToolResultSanitizer sanitizer = (request, toolName, rawArguments, complete) -> {
			sanitizerCalls.incrementAndGet();
			assertEquals(McpOperationType.TASKS_GET, request.getOperationType());
			assertEquals(TOOL, toolName);
			assertEquals(arguments("original arguments"), rawArguments);
			assertEquals(original, complete);
			String caller = (String) request.getAdmissionIdentity().getPrincipal().orElseThrow();
			assertTrue(Set.of("alice", "bob").contains(caller));
			return complete.toBuilder().payload(McpToolOutput.fromStructuredContent(
					McpJsonObject.builder().put("value", "safe-for-" + caller).build()))
					.metadata(caller.equals("alice") ? metadata("only-alice") : McpJsonObject.emptyInstance()).build();
		};
		createCompletedTask(manager, original, sanitizer);
		assertEquals(0, sanitizerCalls.get(), "A task handle must not sanitize completed output.");
		McpTask stored = manager.requireTask();
		assertNotSame(manager.originalOrigin.get(), stored.getTaskOrigin());
		assertEquals(manager.originalOrigin.get(), stored.getTaskOrigin());
		AtomicInteger replacementCalls = new AtomicInteger();
		McpToolRegistration<ModeArguments> incompatibleCurrentRegistration = McpToolRegistration.withName(TOOL)
				.argumentAndOutputTypes(ModeArguments.class, IncompatibleOutput.class)
				.handler((request, arguments, features) -> {
					replacementCalls.incrementAndGet();
					return new IncompatibleOutput("current registration must not run");
				}).structuredContentMirroredAsText(false).build();
		run(List.of(incompatibleCurrentRegistration), sanitizer, manager,
				McpHandlerInterceptor.passThroughInstance(), simulator -> {
			List<String> callers = List.of("alice", "bob", "alice");
			for (int index = 0; index < 3; index++) {
				String caller = callers.get(index);
				Capture response = getTask(simulator, caller);
				assertEquals(200, response.status(), response.body());
				McpJsonObject complete = object(result(response).find("result").orElseThrow());
				assertEquals(McpJsonObject.builder().put("value", "safe-for-" + caller).build(),
						complete.find("structuredContent").orElseThrow());
				assertEquals("{\"value\":\"safe-for-" + caller + "\"}", contentText(complete));
				if (caller.equals("alice"))
					assertEquals(metadata("only-alice"), complete.find("_meta").orElseThrow());
				else
					assertTrue(complete.find("_meta").isEmpty());
				assertFalse(response.body().contains(SECRET), response.body());
				assertEquals(metadata("task-status-not-sanitized"), result(response).find("_meta").orElseThrow());
				assertSame(stored, manager.requireTask());
				assertSame(original, manager.requireTask().getCompletedResult().orElseThrow());
			}
			Capture denied = getTask(simulator, "denied");
			assertEquals(400, denied.status(), denied.body());
			assertFalse(denied.body().contains(SECRET), denied.body());
			assertEquals(3, sanitizerCalls.get());
			assertEquals(0, replacementCalls.get());
		});
	}

	@Test
	void completedTaskSanitizerFailuresRemainClosedAndDoNotOverwriteTheTask() {
		StoredTaskManager manager = new StoredTaskManager();
		McpCompleteResult original = McpCompleteResult.withToolOutput(McpToolOutput.fromStructuredContent(
				McpJsonObject.builder().put("value", SECRET).build())).metadata(metadata(SECRET)).build();
		AtomicReference<String> failureMode = new AtomicReference<>("prompt");
		McpToolResultSanitizer sanitizer = (request, toolName, rawArguments, result) -> {
			assertEquals(McpOperationType.TASKS_GET, request.getOperationType());
			assertEquals(TOOL, toolName);
			assertEquals(arguments("original arguments"), rawArguments);
			assertSame(original, result);
			return switch (failureMode.get()) {
				case "bounds" -> result.toBuilder().metadata(deeplyNestedMetadata(PARTIAL)).build();
				case "schema" -> McpCompleteResult.withToolOutput(McpToolOutput.fromStructuredContent(
						McpJsonObject.builder().put("unexpected", PARTIAL).build())).metadata(metadata(PARTIAL)).build();
				case "healthy" -> McpCompleteResult.withToolOutput(McpToolOutput.fromStructuredContent(
						McpJsonObject.builder().put("value", "healthy").build())).build();
				default -> invalidResult(failureMode.get());
			};
		};
		createCompletedTask(manager, original, sanitizer);
		McpTask stored = manager.requireTask();
		run(List.of(jsonTool(complete("current handler must not run", McpJsonObject.emptyInstance()))),
				sanitizer, manager, McpHandlerInterceptor.passThroughInstance(), simulator -> {
			List<String> failureModes = List.of("prompt", "resource", "null", "exception", "bounds", "schema");
			for (int index = 0; index < 6; index++) {
				failureMode.set(failureModes.get(index));
				assertFixedFailure(getTask(simulator, "alice"));
				assertSame(stored, manager.requireTask());
				assertSame(original, manager.requireTask().getCompletedResult().orElseThrow());
			}
			failureMode.set("healthy");
			Capture response = getTask(simulator, "bob");
			assertEquals(200, response.status(), response.body());
			assertFalse(response.body().contains(SECRET), response.body());
			assertEquals("{\"value\":\"healthy\"}", contentText(object(result(response).find("result").orElseThrow())));
			assertSame(stored, manager.requireTask());
		});
	}

	private static void createCompletedTask(StoredTaskManager manager, McpCompleteResult original,
			McpToolResultSanitizer sanitizer) {
		McpToolRegistration<ModeArguments> tool = McpToolRegistration.withName(TOOL)
				.argumentAndOutputTypes(ModeArguments.class, SafeOutput.class)
				.operationHandler((request, arguments, features) -> {
					manager.store(features.getTaskCreationContext().orElseThrow().getTaskOrigin(), original);
					return McpTaskCreatedResult.<SafeOutput>fromTaskId(TASK);
				}).build();
		run(List.of(tool), sanitizer, manager, McpHandlerInterceptor.passThroughInstance(), simulator -> {
			Capture response = call(simulator, "original arguments", "creator", false);
			assertEquals(200, response.status(), response.body());
			assertEquals(McpJsonString.fromValue("task"), result(response).find("resultType").orElseThrow());
			assertTrue(result(response).find("result").isEmpty());
			assertFalse(response.body().contains(SECRET), response.body());
		});
	}

	private static McpCompleteResult invalidResult(String mode) {
		return switch (mode) {
			case "prompt" -> McpCompleteResult.withPromptOutput(McpPromptOutput.fromMessages())
					.metadata(metadata(PARTIAL)).build();
			case "resource" -> McpCompleteResult.withResourceOutput(McpResourceOutput.fromContent(
					McpTextResourceContents.withUriAndText(URI.create("data:test"), PARTIAL).build()))
					.metadata(metadata(PARTIAL)).build();
			case "null" -> null;
			case "exception" -> throw new IllegalStateException(PARTIAL);
			default -> throw new AssertionError("Unexpected test mode.");
		};
	}

	private static McpToolRegistration<McpJsonObject> jsonTool(McpCompleteResult result) {
		return McpToolRegistration.withName(TOOL).jsonObjectArguments()
				.handler((request, arguments, features) -> result).build();
	}

	private static McpCompleteResult complete(String text, McpJsonObject metadata) {
		return McpCompleteResult.withToolOutput(McpToolOutput.fromText(text)).metadata(metadata).build();
	}

	private static McpJsonObject metadata(String value) {
		return metadata(McpJsonString.fromValue(value));
	}

	private static McpJsonObject metadata(McpJsonValue value) {
		return McpJsonObject.builder().put(META, value).build();
	}

	private static McpJsonObject deeplyNestedMetadata(String leaf) {
		McpJsonValue value = McpJsonString.fromValue(leaf);
		for (int index = 0; index <= McpJsonLimits.productionDefaults().maximumNestingDepth(); index++)
			value = McpJsonObject.builder().put("next", value).build();
		return metadata(value);
	}

	private static McpJsonObject arguments(String mode) {
		return McpJsonObject.builder().put("mode", mode).build();
	}

	private static String mode(McpJsonObject arguments) {
		return ((McpJsonString) arguments.find("mode").orElseThrow()).getValue();
	}

	private static void run(List<? extends McpToolRegistration<?>> tools,
			McpToolResultSanitizer sanitizer, McpTaskManager manager,
			McpHandlerInterceptor interceptor, Consumer<Simulator> test) {
		run(tools, sanitizer, manager, interceptor, null, test);
	}

	private static void run(List<? extends McpToolRegistration<?>> tools,
			McpToolResultSanitizer sanitizer, McpTaskManager manager,
			McpHandlerInterceptor interceptor, LifecycleObserver observer, Consumer<Simulator> test) {
		McpEndpoint.Builder endpoint = McpEndpoint.withPath(PATH,
				McpImplementation.withNameAndVersion("result-sanitizer-test", "test").build()).serverInfoIncluded(false);
		endpoint.toolRegistrations(new java.util.ArrayList<>(tools));
		McpServer.Builder server = McpServer.withPort(0).host("127.0.0.1")
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint.build())))
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance()).allowedHosts(Set.of("127.0.0.1"))
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolResultSanitizer(sanitizer).handlerInterceptor(interceptor)
				.admissionController(context -> {
					String caller = context.getRequest().getHeader("X-Caller").orElseThrow();
					return McpAdmissionDecision.accepted(McpAdmissionIdentity.withRateLimitPartitionKey("rate-" + caller)
							.authorizationPartitionKey(caller.equals("denied") ? "other-tenant" : "shared-tenant")
							.principal(caller).build());
				});
		if (manager != null)
			server.taskManager(manager);
		SokletSimulator.run(SokletConfig.withMcpServer(server.build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(observer)
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY).build(), test::accept);
	}

	private static Capture call(Simulator simulator, String mode, String caller, boolean apps) {
		return execute(simulator, request("tools/call", TOOL, caller,
				McpJsonObject.builder().put("name", TOOL).put("arguments", arguments(mode)).build(), apps));
	}

	private static Capture getTask(Simulator simulator, String caller) {
		return execute(simulator, request("tasks/get", TASK, caller,
				McpJsonObject.builder().put("taskId", TASK).build(), false));
	}

	private static Request request(String method, String name, String caller, McpJsonObject fields, boolean apps) {
		McpJsonObject.Builder extensions = McpJsonObject.builder().put("io.modelcontextprotocol/tasks", McpJsonObject.emptyInstance());
		if (apps)
			extensions.put("io.modelcontextprotocol/ui", McpJsonObject.builder()
					.put("mimeTypes", McpJsonArray.builder().add("text/html;profile=mcp-app").build()).build());
		Map<String, McpJsonValue> params = new LinkedHashMap<>(fields.getMembers());
		params.put("_meta", McpJsonObject.builder().put("io.modelcontextprotocol/protocolVersion", "2026-07-28")
				.put("io.modelcontextprotocol/clientCapabilities", McpJsonObject.builder().put("extensions", extensions.build()).build()).build());
		McpJsonObject body = McpJsonObject.builder().put("jsonrpc", "2.0").put("id", "request")
				.put("method", method).put("params", McpJsonObject.fromMembers(params)).build();
		return Request.withPath(HttpMethod.POST, PATH).headers(Map.of(
				"Host", Set.of("127.0.0.1:0"), "X-Caller", Set.of(caller),
				"Content-Type", Set.of("application/json"), "Accept", Set.of("application/json, text/event-stream"),
				"MCP-Protocol-Version", Set.of("2026-07-28"), "Mcp-Method", Set.of(method), "Mcp-Name", Set.of(name)))
				.body(JSON.toUtf8Bytes(McpPublicJsonValueConverter.toInternal(body))).build();
	}

	private static Capture execute(Simulator simulator, Request request) {
		try (McpSimulation simulation = simulator.startMcpRequest(request)) {
			McpSimulationResponse response = simulation.awaitResponse(WAIT).orElseThrow();
			String body = new String(response.getBody().orElseThrow(), StandardCharsets.UTF_8);
			simulation.awaitCompletion(WAIT).orElseThrow();
			assertEquals(Set.of("no-store"), response.getHeaders().get("Cache-Control"));
			return new Capture(response.getStatusCode(), body);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	private static McpJsonObject result(Capture capture) {
		return object(object(McpPublicJsonValueConverter.toPublic(JSON.parse(capture.body()))).find("result").orElseThrow());
	}

	private static McpJsonObject object(McpJsonValue value) {
		return assertInstanceOf(McpJsonObject.class, value);
	}

	private static String contentText(McpJsonObject complete) {
		McpJsonArray content = (McpJsonArray) complete.find("content").orElseThrow();
		return ((McpJsonString) object(content.getElements().get(0)).find("text").orElseThrow()).getValue();
	}

	private static void assertFixedFailure(Capture response) {
		assertEquals(500, response.status(), response.body());
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\"request\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}",
				response.body());
		assertFalse(response.body().contains(SECRET), response.body());
		assertFalse(response.body().contains(PARTIAL), response.body());
	}

	public record ModeArguments(String mode) {}
	public record SafeOutput(String value) {}
	public record IncompatibleOutput(String incompatible) {}
	private record Capture(int status, String body) {}

	private static final class StoredTaskManager implements McpTaskManager {
		private final AtomicReference<McpTask> stored = new AtomicReference<>();
		private final AtomicReference<McpTaskOrigin> originalOrigin = new AtomicReference<>();

		void store(McpTaskOrigin origin, McpCompleteResult result) {
			this.originalOrigin.set(origin);
			Instant now = Instant.now();
			this.stored.set(McpTask.withTaskId(TASK, McpTaskOrigin.fromPersistedString(origin.toPersistedString()),
					McpTaskStatus.COMPLETED, now, now).timeToLive(Duration.ofHours(1))
					.completedResult(result).metadata(metadata("task-status-not-sanitized")).build());
		}

		McpTask requireTask() {
			return Optional.ofNullable(this.stored.get()).orElseThrow();
		}

		@Override
		public Optional<McpTask> findTask(McpTaskRequestContext context) {
			return Optional.ofNullable(this.stored.get()).filter(task -> task.getTaskId().equals(context.getTaskId())
					&& context.getRequestContext().getEndpoint().getPath().equals(PATH)
					&& context.getRequestContext().getAdmissionIdentity().getAuthorizationPartitionKey()
							.filter("shared-tenant"::equals).isPresent());
		}

		@Override
		public void updateTask(McpTaskUpdateContext context) {
			throw new AssertionError("This test never updates tasks.");
		}

		@Override
		public void requestTaskCancelation(McpTaskRequestContext context) {
			throw new AssertionError("This test never cancels tasks.");
		}
	}
}
