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

package com.soklet.conformance;

import com.soklet.CorsAuthorizer;
import com.soklet.LifecycleObserver;
import com.soklet.LifecyclePolicy;
import com.soklet.McpAbsentOriginPolicy;
import com.soklet.McpAudioContent;
import com.soklet.McpBlobResourceContents;
import com.soklet.McpCachePolicy;
import com.soklet.McpCompleteResult;
import com.soklet.McpContentBlock;
import com.soklet.McpEmbeddedResource;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImageContent;
import com.soklet.McpImplementation;
import com.soklet.McpInputRequest;
import com.soklet.McpInputRequestDeclaration;
import com.soklet.McpInputRequiredResult;
import com.soklet.McpInputRequirement;
import com.soklet.McpJsonArray;
import com.soklet.McpJsonNumber;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonRpcError;
import com.soklet.McpJsonRpcException;
import com.soklet.McpJsonString;
import com.soklet.McpJsonValue;
import com.soklet.McpSubscriptionEventPublisher;
import com.soklet.McpOfficialSchemaConformanceTool;
import com.soklet.McpPromptArgumentDeclaration;
import com.soklet.McpPromptMessage;
import com.soklet.McpPromptOutput;
import com.soklet.McpPromptRegistration;
import com.soklet.McpProgressReporter;
import com.soklet.McpProgressUpdate;
import com.soklet.McpProtectionConfig;
import com.soklet.McpProtectionKey;
import com.soklet.McpProtectionKeyring;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpRateLimiter;
import com.soklet.McpAdmissionController;
import com.soklet.McpResourceContents;
import com.soklet.McpResourcePage;
import com.soklet.McpResourceRegistration;
import com.soklet.McpResourceOutput;
import com.soklet.McpRequestStateMode;
import com.soklet.McpRequestContext;
import com.soklet.McpServer;
import com.soklet.McpServerStatus;
import com.soklet.ShutdownComponentDisposition;
import com.soklet.McpTask;
import com.soklet.McpTaskControl;
import com.soklet.McpTaskCreatedResult;
import com.soklet.McpTaskEventPublisher;
import com.soklet.McpTaskManager;
import com.soklet.McpTaskNotFoundException;
import com.soklet.McpTaskRequestContext;
import com.soklet.McpTaskStatus;
import com.soklet.McpTaskUpdateContext;
import com.soklet.McpSubscriptionConfig;
import com.soklet.McpSubscriptionNotificationType;
import com.soklet.McpTextContent;
import com.soklet.McpTextResourceContents;
import com.soklet.McpToolOutput;
import com.soklet.McpToolRegistration;
import com.soklet.MetricsCollector;
import com.soklet.ShutdownComponentType;
import com.soklet.ResourceMethodResolver;
import com.soklet.SimulatorConfig;
import com.soklet.ShutdownResult;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.annotation.McpHeader;
import com.soklet.annotation.McpToolProperty;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Candidate-artifact black-box fixture for a selected official MCP
 * conformance scenario.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public final class McpConformanceFixture {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final URI STATIC_TEXT_URI = URI.create("test://static-text");
	private static final URI STATIC_BINARY_URI =
			URI.create("test://static-binary");
	private static final String TEMPLATE_URI = "test://template/{id}/data";
	private static final Set<String> ELICITATION_TOOL_SCENARIOS = Set.of(
			"input-required-result-basic-elicitation",
			"input-required-result-missing-input-response",
			"input-required-result-result-type",
			"input-required-result-ignore-extra-params",
			"input-required-result-validate-input");
	private static final Set<String> TASK_SCENARIOS = Set.of(
			"tasks-lifecycle",
			"tasks-capability-negotiation",
			"tasks-wire-fields",
			"tasks-request-state-removal",
			"tasks-mrtr-input",
			"tasks-request-headers",
			"tasks-dispatch-and-envelope",
			"tasks-required-task-error",
			"tasks-mrtr-composition",
			"tasks-status-notifications");
	private static final TaskFixtureManager TASK_MANAGER =
			new TaskFixtureManager();
	private static final McpInputRequestDeclaration FORM_INPUT =
			McpInputRequestDeclaration.fromElicitationForm(
					McpInputRequirement.REQUIRED);
	private static final McpInputRequestDeclaration SAMPLING_INPUT =
			McpInputRequestDeclaration.fromSampling(Set.of(),
					McpInputRequirement.REQUIRED);
	private static final McpInputRequestDeclaration ROOTS_INPUT =
			McpInputRequestDeclaration.fromRoots(McpInputRequirement.REQUIRED);
	private static final McpCachePolicy CACHE_POLICY =
			McpCachePolicy.fromPublicTimeToLive(Duration.ofMinutes(5));
	private static final LifecyclePolicy LIFECYCLE_POLICY =
			LifecyclePolicy.builder()
					.startupCancelationTimeout(Duration.ofSeconds(1))
					.gracefulShutdownTimeout(Duration.ofSeconds(5))
					.forcedShutdownTimeout(Duration.ofSeconds(1))
					.build();
	private static final McpProtectionConfig REQUEST_STATE_PROTECTION =
			McpProtectionConfig.withKeyring(McpProtectionKeyring.withActiveKey(
					McpProtectionKey.fromIdAndBytes("conformance-v1",
							"0123456789abcdef0123456789abcdef"
									.getBytes(StandardCharsets.US_ASCII)))
					.build()).build();
	private static final byte[] PNG_BYTES = Base64.getDecoder().decode(
			"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl7r94AAAAASUVORK5CYII=");
	private static final byte[] WAV_BYTES = Base64.getDecoder().decode(
			"UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA=");
	private static final Set<String> SUPPORTED_SCENARIOS = Set.of(
			"server-stateless",
			"tools-list",
			"tools-call-simple-text",
			"tools-call-image",
			"tools-call-audio",
			"tools-call-embedded-resource",
			"tools-call-mixed-content",
			"tools-call-error",
			"tools-call-with-progress",
			"json-schema-2020-12",
			"server-sse-multiple-streams",
			"resources-list",
			"resources-read-text",
			"resources-read-binary",
			"resources-templates-read",
			"sep-2164-resource-not-found",
			"prompts-list",
			"prompts-get-simple",
			"prompts-get-with-args",
			"prompts-get-embedded-resource",
			"prompts-get-with-image",
			"dns-rebinding-protection",
			"caching",
			"http-header-validation",
			"http-custom-header-server-validation",
			"input-required-result-basic-elicitation",
			"input-required-result-basic-sampling",
			"input-required-result-basic-list-roots",
			"input-required-result-request-state",
			"input-required-result-multiple-input-requests",
			"input-required-result-multi-round",
			"input-required-result-missing-input-response",
			"input-required-result-non-tool-request",
			"input-required-result-result-type",
			"input-required-result-unsupported-methods",
			"input-required-result-tampered-state",
			"input-required-result-capability-check",
			"input-required-result-ignore-extra-params",
			"input-required-result-validate-input",
			"tasks-lifecycle",
			"tasks-capability-negotiation",
			"tasks-wire-fields",
			"tasks-request-state-removal",
			"tasks-mrtr-input",
			"tasks-request-headers",
			"tasks-dispatch-and-envelope",
			"tasks-required-task-error",
			"tasks-mrtr-composition",
			"tasks-status-notifications");

	private McpConformanceFixture() {
	}

	public static void main(String[] arguments) throws Exception {
		if (arguments.length != 2 || !"--scenario".equals(arguments[0])
				|| !SUPPORTED_SCENARIOS.contains(arguments[1]))
			throw new IllegalArgumentException(
					"Usage: McpConformanceFixture --scenario <supported scenario>");

		AtomicInteger effectivePort = new AtomicInteger(-1);
		CorsAuthorizer corsAuthorizer = CorsAuthorizer.fromWhitelistAuthorizer(
				origin -> origin.equals("http://" + LOOPBACK + ":"
						+ effectivePort.get()));
		LifecycleObserver lifecycleObserver = LifecycleObserver.defaultInstance();
		SokletConfig config = configForScenario(arguments[1], corsAuthorizer,
				lifecycleObserver);
		McpServer mcpServer = config.getMcpServer().orElseThrow();
		ShutdownResult shutdownResult;

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			InetSocketAddress address = mcpServer.getDiagnostics().getBoundAddress()
					.orElseThrow(() -> new IllegalStateException(
							"The public MCP server did not publish its bound address."));
			if (!address.getAddress().isLoopbackAddress())
				throw new IllegalStateException(
						"The conformance fixture did not bind a loopback address.");
			effectivePort.set(address.getPort());
			writeControlLine("{\"format\":1,\"event\":\"ready\","
					+ "\"host\":\"" + LOOPBACK + "\",\"port\":"
					+ address.getPort() + ",\"path\":\"" + MCP_PATH + "\"}");

			while (System.in.read() >= 0) {
				// The parent owns this pipe. EOF is the graceful shutdown request.
			}
			soklet.shutdown();
			shutdownResult = soklet.awaitShutdown();
		}

		if (mcpServer.getDiagnostics().getStatus()
				!= McpServerStatus.TERMINATED
				|| shutdownResult.getShutdownComponentResult(ShutdownComponentType.MCP)
						.orElseThrow().getShutdownComponentDisposition()
						!= ShutdownComponentDisposition.GRACEFUL_TERMINATION)
			throw new IllegalStateException(
					"The public MCP conformance fixture did not shut down cleanly.");

		writeControlLine("{\"format\":1,\"event\":\"stopped\",\"clean\":true}");
	}

	static SimulatorConfig simulationConfigForScenario(String scenario,
			MetricsCollector metricsCollector,
			LifecycleObserver lifecycleObserver) {
		requireSupportedScenario(scenario);
		McpEndpoint endpoint = endpointForScenario(scenario);
		CorsAuthorizer corsAuthorizer =
				CorsAuthorizer.fromWhitelistAuthorizer(origin ->
						origin.equals("http://" + LOOPBACK + ":0"));
		SimulatorConfig.Builder configured = SimulatorConfig.builder()
				.configureMcpServer(builder -> configureMcpServerForScenario(
						scenario, corsAuthorizer, builder
								.port(0)
								.endpointRegistry(McpEndpointRegistry.fromEndpoints(
										List.of(endpoint)))
								.admissionController(
										McpAdmissionController.acceptAllInstance())))
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(lifecycleObserver)
				.lifecyclePolicy(LIFECYCLE_POLICY);
		if (metricsCollector != null)
			configured.metricsCollector(metricsCollector);
		return configured.build();
	}

	private static SokletConfig configForScenario(String scenario,
			CorsAuthorizer corsAuthorizer,
			LifecycleObserver lifecycleObserver) {
		McpEndpoint endpoint = endpointForScenario(scenario);
		McpServer mcpServer = mcpServerForScenario(scenario, corsAuthorizer,
				McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))));
		return SokletConfig.withMcpServer(mcpServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(lifecycleObserver)
				.lifecyclePolicy(LIFECYCLE_POLICY)
				.build();
	}

	private static McpServer mcpServerForScenario(String scenario,
			CorsAuthorizer corsAuthorizer,
			McpServer.Builder mcpServerBuilder) {
		requireSupportedScenario(scenario);
		return configureMcpServerForScenario(scenario, corsAuthorizer,
				mcpServerBuilder)
				.build();
	}

	private static McpServer.Builder configureMcpServerForScenario(
			String scenario, CorsAuthorizer corsAuthorizer,
			McpServer.Builder mcpServerBuilder) {
		requireSupportedScenario(scenario);
		McpRateLimiter allowLimiter = context ->
				McpRateLimitDecision.allowed();
		McpServer.Builder configured = mcpServerBuilder
				.host(LOOPBACK)
				.requestRateLimiter(allowLimiter)
				.toolRateLimiter(allowLimiter)
				.protectionConfig(REQUEST_STATE_PROTECTION)
				.corsAuthorizer(corsAuthorizer)
				.absentOriginPolicy(McpAbsentOriginPolicy.ALLOW)
				.allowedHosts(Set.of(LOOPBACK));
		if (TASK_SCENARIOS.contains(scenario))
			configured.taskManager(TASK_MANAGER);
		return configured;
	}

	private static void requireSupportedScenario(String scenario) {
		if (!SUPPORTED_SCENARIOS.contains(scenario))
			throw new IllegalArgumentException(
					"Unsupported MCP conformance scenario: " + scenario);
	}

	static McpEndpoint endpointForScenario(String scenario) {
		McpEndpoint.Builder builder = McpEndpoint.withPath(MCP_PATH, McpImplementation.withNameAndVersion(
						"soklet-public-conformance", "4.0.0")
						.description("Soklet MCP conformance fixture")
						.build())
				.serverInformationIncluded(true)
				.addTools(tools(scenario))
				.addPrompts(prompts(scenario))
				.addResources(resources())
				.resourceListHandler((request, list, features) -> {
					if (list.getCursor().isPresent())
						throw new McpJsonRpcException(
								McpJsonRpcError.fromInvalidParameters(
										"The resource-list cursor is invalid."));
					return McpResourcePage.builder()
							.addResources(list.getRegisteredResourceDescriptors())
							.build();
				})
				.resourceListCachePolicy(CACHE_POLICY)
				.resourceTemplateListCachePolicy(CACHE_POLICY);
		if ("server-stateless".equals(scenario))
			builder.subscriptionConfig(McpSubscriptionConfig.withEventPublisher(
					McpSubscriptionEventPublisher.fromInMemoryDefaults(), Set.of(
							McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED,
							McpSubscriptionNotificationType.RESOURCE_UPDATED))
					.build());
		return builder.build();
	}

	private static List<McpToolRegistration<?>> tools(String scenario) {
		List<McpToolRegistration<?>> tools = new ArrayList<>(List.of(
				rawTool("test_simple_text",
						"Returns deterministic text content.",
						() -> McpCompleteResult.fromToolText(
								"This is a simple text response for testing.")),
				rawTool("test_image_content",
						"Returns deterministic image content.",
						() -> completeToolOutput(McpImageContent
								.withDataAndMimeType(PNG_BYTES, "image/png")
								.build())),
				rawTool("test_audio_content",
						"Returns deterministic audio content.",
						() -> completeToolOutput(McpAudioContent
								.withDataAndMimeType(WAV_BYTES, "audio/wav")
								.build())),
				rawTool("test_embedded_resource",
						"Returns deterministic embedded-resource content.",
						() -> completeToolOutput(embeddedTextResource(
								URI.create("test://embedded-resource"),
								"text/plain",
								"This is an embedded resource content."))),
				rawTool("test_multiple_content_types",
						"Returns deterministic mixed content.",
						McpConformanceFixture::mixedContentResult),
				rawTool("test_error_handling",
						"Returns a deterministic application-level tool error.",
						() -> McpCompleteResult.fromToolErrorText(
								"This tool intentionally returns an error for testing")),
				McpToolRegistration.withName("test_tool_with_progress")
						.jsonObjectArguments()
						.handler((request, arguments, features) -> {
							McpProgressReporter reporter = features
									.find(McpProgressReporter.class)
									.orElseThrow(() -> new IllegalStateException(
											"The progress scenario omitted its progress token."));
							reporter.report(McpProgressUpdate.withProgress(0.0d)
									.total(100.0d).build());
							reporter.report(McpProgressUpdate.withProgress(50.0d)
									.total(100.0d).build());
							reporter.report(McpProgressUpdate.withProgress(100.0d)
									.total(100.0d).build());
							return McpCompleteResult.fromToolText(
									"Progress test completed.");
						})
						.description("Reports deterministic 0/50/100 progress.")
						.build(),
				McpOfficialSchemaConformanceTool.create(),
				McpToolRegistration.withName("test_custom_header")
						.argumentType(CustomHeaderArguments.class)
						.handler((request, arguments, features) ->
								McpCompleteResult.fromToolText(
										"Custom header accepted."))
						.description(
								"Validates one string-valued custom mirrored header.")
						.build()));
		if ("server-stateless".equals(scenario)) {
			McpInputRequestDeclaration sampling =
					McpInputRequestDeclaration.fromSampling(Set.of(),
							McpInputRequirement.REQUIRED);
			McpInputRequestDeclaration elicitation =
					McpInputRequestDeclaration.fromElicitationForm(
							McpInputRequirement.REQUIRED);
			McpJsonObject elicitationParameters = McpJsonObject.builder()
					.put("message", "Provide a conformance value")
					.put("requestedSchema", McpJsonObject.builder()
							.put("type", "object")
							.put("properties", McpJsonObject.emptyInstance())
							.build())
					.build();
			tools.add(McpToolRegistration.withName("test_missing_capability")
					.jsonObjectArguments()
					.handler((request, arguments, features) ->
							McpCompleteResult.fromToolText(
									"Sampling capability was declared."))
					.addInputRequestDeclarations(sampling)
					.description("Requires the base sampling capability.")
					.build());
			tools.add(McpToolRegistration.withName("test_streaming_elicitation")
					.jsonObjectArguments()
					.handler((request, arguments, features) ->
							McpInputRequiredResult.withInputRequest("conformance-value",
											McpInputRequest.fromDeclaration(
													elicitation,
													elicitationParameters))
									.build())
					.addInputRequestDeclarations(elicitation)
					.description("Returns one embedded elicitation input request.")
					.build());
			tools.add(rawTool("test_logging_tool",
					"Completes without emitting a log notification.",
					() -> McpCompleteResult.fromToolText(
							"No log notification was emitted.")));
		}
		addPhase5Tools(tools, scenario);
		if (TASK_SCENARIOS.contains(scenario))
			addTaskTools(tools);
		return List.copyOf(tools);
	}

	private static void addTaskTools(List<McpToolRegistration<?>> tools) {
		tools.add(McpToolRegistration.withName("greet")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					String name = ((McpJsonString) arguments.getConvertedArguments()
							.find("name").orElse(McpJsonString.fromValue("World")))
							.getValue();
					return McpCompleteResult.fromToolText("Hello, " + name + "!");
				})
				.description("Returns a synchronous greeting.")
				.build());
		tools.add(McpToolRegistration.withName("slow_compute")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					long seconds = integerArgument(arguments.getConvertedArguments(),
							"seconds", 0L);
					String label = stringArgument(arguments.getConvertedArguments(),
							"label", "complete");
					Optional<McpTaskControl> taskControl = features.getTaskControl();
					if (taskControl.isEmpty())
						return McpCompleteResult.fromToolText(
								"Computed " + label + " synchronously.");
					McpTask task = TASK_MANAGER.createTask(taskControl.orElseThrow());
					TASK_MANAGER.completeAfter(task.getTaskId(),
							Duration.ofSeconds(seconds), McpCompleteResult.fromToolText(
									"Computed " + label + "."));
					return McpTaskCreatedResult.<String>fromTaskId(task.getTaskId());
				})
				.description("Completes asynchronously when Tasks are negotiated.")
				.build());
		tools.add(McpToolRegistration.withName("failing_job")
				.argumentAndOutputTypes(EmptyTaskArguments.class, TaskOutput.class)
				.operationHandler((request, arguments, features) -> {
					McpTask task = TASK_MANAGER.createTask(
							features.getTaskControl().orElseThrow());
					TASK_MANAGER.completeAfter(task.getTaskId(),
							Duration.ofMillis(25), McpCompleteResult.fromToolErrorText(
									"The task fixture intentionally failed."));
					return McpTaskCreatedResult.<TaskOutput>fromTaskId(task.getTaskId());
				})
				.description("Completes with an application-level tool error.")
				.build());
		tools.add(McpToolRegistration.withName("protocol_error_job")
				.argumentAndOutputTypes(EmptyTaskArguments.class, TaskOutput.class)
				.operationHandler((request, arguments, features) -> {
					McpTask task = TASK_MANAGER.createTask(
							features.getTaskControl().orElseThrow());
					TASK_MANAGER.failAfter(task.getTaskId(), Duration.ofMillis(25),
							McpJsonRpcError.fromApplication(50001,
									"Task fixture protocol failure."));
					return McpTaskCreatedResult.<TaskOutput>fromTaskId(task.getTaskId());
				})
				.description("Fails with a protocol-level task error.")
				.build());
		tools.add(McpToolRegistration.withName("confirm_delete")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					Optional<McpTaskControl> taskControl = features.getTaskControl();
					if (taskControl.isEmpty())
						return McpCompleteResult.fromToolText(
								"Tasks were not negotiated.");
					McpTask task = TASK_MANAGER.createTask(
							taskControl.orElseThrow());
					TASK_MANAGER.requestTaskInput(task.getTaskId(), Map.of(
							"confirmation", formInput("Confirm deletion", "confirm",
									"boolean")));
					return McpTaskCreatedResult.<String>fromTaskId(task.getTaskId());
				})
				.addInputRequestDeclarations(FORM_INPUT)
				.description("Waits for one task-scoped elicitation response.")
				.build());
		tools.add(McpToolRegistration.withName("multi_input")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					Optional<McpTaskControl> taskControl = features.getTaskControl();
					if (taskControl.isEmpty())
						return McpCompleteResult.fromToolText(
								"Tasks were not negotiated.");
					McpTask task = TASK_MANAGER.createTask(
							taskControl.orElseThrow());
					TASK_MANAGER.requestTaskInput(task.getTaskId(), Map.of(
							"first", formInput("First task input", "name", "string"),
							"second", formInput("Second task input", "confirm",
									"boolean")));
					return McpTaskCreatedResult.<String>fromTaskId(task.getTaskId());
				})
				.addInputRequestDeclarations(FORM_INPUT)
				.description("Waits for two task-scoped elicitation responses.")
				.build());
		tools.add(McpToolRegistration.withName("test_tool_with_task")
				.argumentAndOutputTypes(EmptyTaskArguments.class, TaskOutput.class)
				.operationHandler((request, arguments, features) -> {
					if (request.getInputResponses().find("user_name").isEmpty())
						return McpInputRequiredResult.withInputRequest("user_name",
								formInput("What is your name?", "name", "string"))
								.build();
					McpTask task = TASK_MANAGER.createTask(
							features.getTaskControl().orElseThrow());
					TASK_MANAGER.completeNow(task.getTaskId(),
							McpCompleteResult.fromToolStructuredContent(
									McpJsonObject.builder()
											.put("message", "Hello, Alice!")
											.build()));
					return McpTaskCreatedResult.<TaskOutput>fromTaskId(task.getTaskId());
				})
				.addInputRequestDeclarations(FORM_INPUT)
				.description("Composes an MRTR response with task creation.")
				.build());
	}

	private static long integerArgument(McpJsonObject arguments, String name,
			long defaultValue) {
		return arguments.find(name)
				.filter(McpJsonNumber.class::isInstance)
				.map(McpJsonNumber.class::cast)
				.map(number -> number.getValue().longValueExact())
				.orElse(defaultValue);
	}

	private static String stringArgument(McpJsonObject arguments, String name,
			String defaultValue) {
		return arguments.find(name)
				.filter(McpJsonString.class::isInstance)
				.map(McpJsonString.class::cast)
				.map(McpJsonString::getValue)
				.orElse(defaultValue);
	}

	private static void addPhase5Tools(List<McpToolRegistration<?>> tools,
			String scenario) {
		if (ELICITATION_TOOL_SCENARIOS.contains(scenario))
			tools.add(elicitationTool());
		else if ("input-required-result-basic-sampling".equals(scenario))
			tools.add(samplingTool());
		else if ("input-required-result-basic-list-roots".equals(scenario))
			tools.add(listRootsTool());
		else if ("input-required-result-request-state".equals(scenario))
			tools.add(requestStateTool());
		else if ("input-required-result-multiple-input-requests".equals(scenario))
			tools.add(multipleInputsTool());
		else if ("input-required-result-multi-round".equals(scenario))
			tools.add(multiRoundTool());
		else if ("input-required-result-tampered-state".equals(scenario))
			tools.add(tamperedStateTool());
		else if ("input-required-result-capability-check".equals(scenario))
			tools.add(capabilityTool());
	}

	private static McpToolRegistration<McpJsonObject> elicitationTool() {
		return McpToolRegistration.withName(
				"test_input_required_result_elicitation")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					if (request.getInputResponses().find("user_name").isPresent())
						return McpCompleteResult.fromToolText("Hello, Alice!");
					return McpInputRequiredResult.withInputRequest("user_name", formInput(
									"What is your name?", "name", "string"))
							.build();
				})
				.addInputRequestDeclarations(FORM_INPUT)
				.description("Collects a user name through embedded elicitation.")
				.build();
	}

	private static McpToolRegistration<McpJsonObject> samplingTool() {
		return McpToolRegistration.withName(
				"test_input_required_result_sampling")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					if (request.getInputResponses().find(
							"capital_question").isPresent())
						return McpCompleteResult.fromToolText(
								"The capital of France is Paris.");
					return McpInputRequiredResult.withInputRequest("capital_question", samplingInput(
									"What is the capital of France?", 100))
							.build();
				})
				.addInputRequestDeclarations(SAMPLING_INPUT)
				.description("Collects a sampling answer about France.")
				.build();
	}

	private static McpToolRegistration<McpJsonObject> listRootsTool() {
		return McpToolRegistration.withName(
				"test_input_required_result_list_roots")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					if (request.getInputResponses().find("client_roots").isPresent())
						return McpCompleteResult.fromToolText(
								"Client root file:///test/root accepted.");
					return McpInputRequiredResult.withInputRequest("client_roots", rootsInput())
							.build();
				})
				.addInputRequestDeclarations(ROOTS_INPUT)
				.description("Collects the current client roots.")
				.build();
	}

	private static McpToolRegistration<McpJsonObject> requestStateTool() {
		return McpToolRegistration.withName(
				"test_input_required_result_request_state")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					if (hasFrameworkState(request, "request-state")
							&& request.getInputResponses().find("confirm").isPresent())
						return McpCompleteResult.fromToolText("state-ok");
					return McpInputRequiredResult.withInputRequest("confirm", formInput(
									"Please confirm", "ok", "boolean"))
							.frameworkRequestState(McpJsonString.fromValue("request-state"))
							.build();
				})
				.addInputRequestDeclarations(FORM_INPUT)
				.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
				.description("Verifies protected request-state round trips.")
				.build();
	}

	private static McpToolRegistration<McpJsonObject> multipleInputsTool() {
		return McpToolRegistration.withName(
				"test_input_required_result_multiple_inputs")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					boolean complete = hasFrameworkState(request, "multiple-inputs")
							&& request.getInputResponses().find("user_name").isPresent()
							&& request.getInputResponses().find("greeting").isPresent()
							&& request.getInputResponses().find("client_roots").isPresent();
					if (complete)
						return McpCompleteResult.fromToolText(
								"All input responses accepted.");
					return McpInputRequiredResult.withInputRequest("user_name", formInput(
									"What is your name?", "name", "string"))
							.addInputRequest("greeting", samplingInput(
									"Generate a greeting", 50))
							.addInputRequest("client_roots", rootsInput())
							.frameworkRequestState(McpJsonString.fromValue("multiple-inputs"))
							.build();
				})
				.addInputRequestDeclarations(FORM_INPUT, SAMPLING_INPUT, ROOTS_INPUT)
				.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
				.description("Collects elicitation, sampling, and roots responses.")
				.build();
	}

	private static McpToolRegistration<McpJsonObject> multiRoundTool() {
		return McpToolRegistration.withName(
				"test_input_required_result_multi_round")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					if (hasFrameworkState(request, "round-2")
							&& request.getInputResponses().find("step2").isPresent())
						return McpCompleteResult.fromToolText(
								"Multi-round input complete.");
					if (hasFrameworkState(request, "round-1")
							&& request.getInputResponses().find("step1").isPresent())
						return McpInputRequiredResult.withInputRequest("step2", formInput(
										"Step 2: What is your favorite color?",
										"color", "string"))
								.frameworkRequestState(McpJsonString.fromValue("round-2"))
								.build();
					return McpInputRequiredResult.withInputRequest("step1", formInput(
									"Step 1: What is your name?", "name", "string"))
							.frameworkRequestState(McpJsonString.fromValue("round-1"))
							.build();
				})
				.addInputRequestDeclarations(FORM_INPUT)
				.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
				.description("Collects input over two protected rounds.")
				.build();
	}

	private static McpToolRegistration<McpJsonObject> tamperedStateTool() {
		return McpToolRegistration.withName(
				"test_input_required_result_tampered_state")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					if (hasFrameworkState(request, "tamper-check")
							&& request.getInputResponses().find("confirm").isPresent())
						return McpCompleteResult.fromToolText(
								"Protected state accepted.");
					return McpInputRequiredResult.withInputRequest("confirm", formInput(
									"Please confirm", "ok", "boolean"))
							.frameworkRequestState(McpJsonString.fromValue("tamper-check"))
							.build();
				})
				.addInputRequestDeclarations(FORM_INPUT)
				.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
				.description("Rejects modified protected request state.")
				.build();
	}

	private static McpToolRegistration<McpJsonObject> capabilityTool() {
		return McpToolRegistration.withName(
				"test_input_required_result_capabilities")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					if (request.getInputResponses().find("sampling").isPresent())
						return McpCompleteResult.fromToolText(
								"Sampling response accepted.");
					return McpInputRequiredResult.withInputRequest("sampling", samplingInput(
									"Generate one supported response", 50))
							.build();
				})
				.addInputRequestDeclarations(SAMPLING_INPUT)
				.description("Requests only the declared sampling capability.")
				.build();
	}

	private static McpInputRequest formInput(String message, String field,
			String fieldType) {
		McpJsonObject requestedSchema = McpJsonObject.builder()
				.put("type", "object")
				.put("properties", McpJsonObject.builder()
						.put(field, McpJsonObject.builder()
								.put("type", fieldType)
								.build())
						.build())
				.put("required", McpJsonArray.builder().add(field).build())
				.build();
		return McpInputRequest.fromDeclaration(FORM_INPUT,
				McpJsonObject.builder()
						.put("message", message)
						.put("requestedSchema", requestedSchema)
						.build());
	}

	private static McpInputRequest samplingInput(String prompt,
			Integer maximumTokens) {
		McpJsonObject message = McpJsonObject.builder()
				.put("role", "user")
				.put("content", McpJsonObject.builder()
						.put("type", "text")
						.put("text", prompt)
						.build())
				.build();
		return McpInputRequest.fromDeclaration(SAMPLING_INPUT,
				McpJsonObject.builder()
						.put("messages", McpJsonArray.builder().add(message).build())
						.put("maxTokens", maximumTokens)
						.build());
	}

	private static McpInputRequest rootsInput() {
		return McpInputRequest.fromDeclaration(ROOTS_INPUT,
				McpJsonObject.emptyInstance());
	}

	private static boolean hasFrameworkState(
			com.soklet.McpRequestContext request, String expectedValue) {
		if (request.getFrameworkRequestState().isEmpty()
				|| !(request.getFrameworkRequestState().orElseThrow()
				instanceof McpJsonString stateValue))
			return false;
		return expectedValue.equals(stateValue.getValue());
	}

	private static McpToolRegistration<McpJsonObject> rawTool(String name,
			String description, Supplier<McpCompleteResult> resultSupplier) {
		return McpToolRegistration.withName(name)
				.jsonObjectArguments()
				.handler((request, arguments, features) -> resultSupplier.get())
				.description(description)
				.build();
	}

	private static McpCompleteResult mixedContentResult() {
		return McpCompleteResult.fromToolOutput(McpToolOutput.builder()
				.addContent(McpTextContent.fromText("Multiple content types test:"))
				.addContent(McpImageContent.withDataAndMimeType(
						PNG_BYTES, "image/png").build())
				.addContent(embeddedTextResource(
						URI.create("test://mixed-content-resource"),
						"application/json", "{\"test\":\"data\",\"value\":123}"))
				.build());
	}

	private static McpCompleteResult completeToolOutput(
			McpContentBlock content) {
		return McpCompleteResult.fromToolOutput(
				McpToolOutput.builder().addContent(content).build());
	}

	private static McpEmbeddedResource embeddedTextResource(URI uri,
			String mimeType, String text) {
		return McpEmbeddedResource.withResource(McpTextResourceContents
				.withUriAndText(uri, text)
				.mimeType(mimeType)
				.build()).build();
	}

	private static List<McpPromptRegistration> prompts(String scenario) {
		List<McpPromptRegistration> prompts = new ArrayList<>(List.of(
				McpPromptRegistration.withName("test_simple_prompt")
						.handler((request, prompt, features) -> completePrompt(
								McpPromptMessage.fromUserContent(
										McpTextContent.fromText(
												"This is a simple prompt for testing."))))
						.description("Returns a deterministic simple prompt.")
						.build(),
				McpPromptRegistration.withName("test_prompt_with_arguments")
						.handler((request, prompt, features) -> completePrompt(
								McpPromptMessage.fromUserContent(McpTextContent.fromText(
										"Prompt with arguments: arg1='"
												+ prompt.findArgument("arg1").orElseThrow()
												+ "', arg2='"
												+ prompt.findArgument("arg2").orElseThrow()
												+ "'"))))
						.description("Substitutes two required string arguments.")
						.addArgument(requiredPromptArgument("arg1",
								"First test argument"))
						.addArgument(requiredPromptArgument("arg2",
								"Second test argument"))
						.build(),
				McpPromptRegistration.withName(
						"test_prompt_with_embedded_resource")
						.handler((request, prompt, features) -> {
							URI uri = URI.create(prompt.findArgument(
									"resourceUri").orElseThrow());
							return completePrompt(
									McpPromptMessage.fromUserContent(
											embeddedTextResource(uri, "text/plain",
													"Embedded resource content for testing.")),
									McpPromptMessage.fromUserContent(
											McpTextContent.fromText(
													"Please process the embedded resource above.")));
						})
						.description("Embeds the requested text resource.")
						.addArgument(requiredPromptArgument("resourceUri",
								"URI of the resource to embed"))
						.build(),
				McpPromptRegistration.withName("test_prompt_with_image")
						.handler((request, prompt, features) -> completePrompt(
								McpPromptMessage.fromUserContent(McpImageContent
										.withDataAndMimeType(PNG_BYTES, "image/png")
										.build()),
								McpPromptMessage.fromUserContent(McpTextContent.fromText(
										"Please analyze the image above."))))
						.description("Returns deterministic image prompt content.")
						.build()));
		if ("input-required-result-non-tool-request".equals(scenario))
			prompts.add(McpPromptRegistration.withName(
					"test_input_required_result_prompt")
					.handler((request, prompt, features) -> {
						if (request.getInputResponses().find(
								"user_context").isPresent())
							return completePrompt(McpPromptMessage.fromUserContent(
									McpTextContent.fromText(
											"Prompt using test context.")));
						return McpInputRequiredResult.withInputRequest("user_context", formInput(
										"What context should the prompt use?",
										"context", "string"))
								.build();
					})
					.addInputRequestDeclarations(FORM_INPUT)
					.description("Collects context before rendering a prompt.")
					.build());
		return List.copyOf(prompts);
	}

	private static McpPromptArgumentDeclaration requiredPromptArgument(
			String name, String description) {
		return McpPromptArgumentDeclaration.withName(name)
				.description(description)
				.required(true)
				.build();
	}

	private static McpCompleteResult completePrompt(
			McpPromptMessage... messages) {
		return McpCompleteResult.fromPromptOutput(
				McpPromptOutput.fromMessages(messages));
	}

	private static List<McpResourceRegistration> resources() {
		return List.of(
				McpResourceRegistration.withUriAndName(
						STATIC_TEXT_URI, "Static text resource")
						.handler((request, resource, features) ->
								completeResource(McpTextResourceContents
										.withUriAndText(resource.getUri(),
												"This is the content of the static text resource.")
										.mimeType("text/plain")
										.build()))
						.description("A deterministic UTF-8 text resource.")
						.mimeType("text/plain")
						.cachePolicy(CACHE_POLICY)
						.build(),
				McpResourceRegistration.withUriAndName(
						STATIC_BINARY_URI, "Static binary resource")
						.handler((request, resource, features) ->
								completeResource(McpBlobResourceContents
										.withUriAndData(resource.getUri(), PNG_BYTES)
										.mimeType("image/png")
										.build()))
						.description("A deterministic PNG resource.")
						.mimeType("image/png")
						.cachePolicy(CACHE_POLICY)
						.build(),
				McpResourceRegistration.withUriTemplateAndName(
						TEMPLATE_URI, "Template data resource")
						.handler((request, resource, features) -> {
							String id = resource.getUriTemplateVariables()
									.get("id");
							String text = "{\"id\":\"" + id
									+ "\",\"templateTest\":true,\"data\":\"Data for ID: "
									+ id + "\"}";
							return completeResource(McpTextResourceContents
									.withUriAndText(resource.getUri(), text)
									.mimeType("application/json")
									.build());
						})
						.description("A deterministic RFC 6570 Level 1 template.")
						.mimeType("application/json")
						.cachePolicy(CACHE_POLICY)
						.build());
	}

	private static McpCompleteResult completeResource(
			McpResourceContents contents) {
		return McpCompleteResult.fromResourceOutput(
				McpResourceOutput.withContent(contents).build());
	}

	private static void writeControlLine(String line) throws Exception {
		System.out.write((line + '\n').getBytes(StandardCharsets.UTF_8));
		System.out.flush();
	}

	/**
	 * Typed input used by the pinned custom mirrored-header scenario.
	 *
	 * @param value body value mirrored by {@code Mcp-Param-Value}
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	public record CustomHeaderArguments(
			@McpToolProperty(description = "Mirrored test value")
			@McpHeader(name = "Value") String value) {
	}

	/** Empty typed arguments for task-required conformance tools. */
	public record EmptyTaskArguments() {
	}

	/** Typed eventual output retained by task-required conformance tools. */
	private record TaskOutput(String message) {
	}

	/**
	 * Test-only task manager with deterministic process-local worker behavior.
	 * The production library deliberately supplies no worker runtime.
	 */
	private static final class TaskFixtureManager implements McpTaskManager {
		private static final Duration TASK_TIME_TO_LIVE = Duration.ofHours(1);
		private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
		private final Map<String, Entry> entries = new LinkedHashMap<>();
		private final McpTaskEventPublisher eventPublisher =
				McpTaskEventPublisher.fromInMemoryDefaults();

		private synchronized McpTask createTask(McpTaskControl taskControl) {
			McpRequestContext requestContext = taskControl.getRequestContext();
			McpTask task;
			String taskId;
			do {
				taskId = UUID.randomUUID().toString();
			} while (this.entries.containsKey(taskId));
			Instant now = Instant.now();
			task = taskBuilder(taskId, taskControl.getTaskOrigin(),
					McpTaskStatus.WORKING, now, now).build();
			this.entries.put(taskId, new Entry(task,
					requestContext.getEndpoint().getPath(), requestContext
							.getAdmissionIdentity().getAuthorizationPartitionKey()));
			publishChanged(taskId);
			return task;
		}

		private synchronized void requestTaskInput(String taskId,
				Map<String, McpInputRequest> inputRequests)
				throws McpTaskNotFoundException {
			Entry entry = requireEntry(taskId);
			requireActive(entry.task);
			Map<String, McpInputRequest> combined = new LinkedHashMap<>(
					entry.task.getInputRequests());
			combined.putAll(inputRequests);
			entry.task = taskBuilder(entry.task, McpTaskStatus.INPUT_REQUIRED)
					.taskStatusMessage("Waiting for client input.")
					.addInputRequests(combined)
					.build();
			publishChanged(taskId);
		}

		private synchronized void completeNow(String taskId,
				McpCompleteResult result) throws McpTaskNotFoundException {
			Entry entry = requireEntry(taskId);
			requireActive(entry.task);
			entry.task = taskBuilder(entry.task, McpTaskStatus.COMPLETED)
					.taskStatusMessage("Completed.")
					.completedResult(result)
					.build();
			publishChanged(taskId);
		}

		private synchronized void failNow(String taskId,
				McpJsonRpcError failure) throws McpTaskNotFoundException {
			Entry entry = requireEntry(taskId);
			requireActive(entry.task);
			entry.task = taskBuilder(entry.task, McpTaskStatus.FAILED)
					.taskStatusMessage("Failed.")
					.failure(failure)
					.build();
			publishChanged(taskId);
		}

		private void completeAfter(String taskId, Duration delay,
				McpCompleteResult result) {
			launchWorker(taskId, delay, () -> completeNow(taskId, result));
		}

		private void failAfter(String taskId, Duration delay,
				McpJsonRpcError failure) {
			launchWorker(taskId, delay, () -> failNow(taskId, failure));
		}

		private void launchWorker(String taskId, Duration delay,
				TaskTransition transition) {
			Thread worker = new Thread(() -> {
				long deadline = System.nanoTime() + delay.toNanos();
				try {
					while (System.nanoTime() < deadline) {
						if (isTaskCancelationRequested(taskId)) {
							cancelIfActive(taskId);
							return;
						}
						Thread.sleep(10L);
					}
					transition.run();
				} catch (McpTaskNotFoundException | IllegalStateException ignored) {
					// A concurrent terminal transition or expiry won the fixture race.
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
			}, "soklet-conformance-task-" + taskId);
			worker.setDaemon(true);
			worker.start();
		}

		private synchronized void cancelIfActive(String taskId) {
			try {
				Entry entry = requireEntry(taskId);
				if (isTerminal(entry.task.getTaskStatus()))
					return;
				entry.task = taskBuilder(entry.task, McpTaskStatus.CANCELED)
						.taskStatusMessage("Canceled.")
						.build();
				publishChanged(taskId);
			} catch (McpTaskNotFoundException ignored) {
				// The fixture may already have discarded the task.
			}
		}

		private synchronized boolean isTaskCancelationRequested(String taskId)
				throws McpTaskNotFoundException {
			return requireEntry(taskId).cancelationRequested;
		}

		@Override
		public Optional<McpTaskEventPublisher> getTaskEventPublisher() {
			return Optional.of(this.eventPublisher);
		}

		@Override
		public synchronized Optional<McpTask> findTask(
				McpTaskRequestContext context) {
			Entry entry = this.entries.get(context.getTaskId());
			return entry != null && entry.isAuthorized(context.getRequestContext())
					? Optional.of(entry.task) : Optional.empty();
		}

		@Override
		public synchronized void updateTask(McpTaskUpdateContext context)
				throws McpTaskNotFoundException {
			Entry entry = requireAuthorizedEntry(context.getTaskId(),
					context.getRequestContext());
			if (entry.task.getTaskStatus() != McpTaskStatus.INPUT_REQUIRED)
				return;
			Map<String, McpInputRequest> remaining = new LinkedHashMap<>(
					entry.task.getInputRequests());
			boolean accepted = false;
			for (Map.Entry<String, McpJsonValue> response
					: context.getInputResponses().asMap().entrySet()) {
				McpInputRequest inputRequest = remaining.get(response.getKey());
				if (inputRequest != null && inputRequest.matchesInputResponse(
						response.getValue())) {
					remaining.remove(response.getKey());
					accepted = true;
				}
			}
			if (!accepted)
				return;
			if (remaining.isEmpty())
				entry.task = taskBuilder(entry.task, McpTaskStatus.COMPLETED)
						.taskStatusMessage("Completed.")
						.completedResult(McpCompleteResult.fromToolText(
								"Task input accepted."))
						.build();
			else
				entry.task = taskBuilder(entry.task, McpTaskStatus.INPUT_REQUIRED)
						.taskStatusMessage("Waiting for client input.")
						.addInputRequests(remaining)
						.build();
			publishChanged(context.getTaskId());
		}

		@Override
		public synchronized void requestTaskCancelation(
				McpTaskRequestContext context)
				throws McpTaskNotFoundException {
			Entry entry = requireAuthorizedEntry(context.getTaskId(),
					context.getRequestContext());
			if (isTerminal(entry.task.getTaskStatus()))
				return;
			entry.cancelationRequested = true;
			entry.task = taskBuilder(entry.task, McpTaskStatus.CANCELED)
					.taskStatusMessage("Canceled.")
					.build();
			publishChanged(context.getTaskId());
		}

		private synchronized Entry requireEntry(String taskId)
				throws McpTaskNotFoundException {
			Entry entry = this.entries.get(taskId);
			if (entry == null)
				throw new McpTaskNotFoundException();
			return entry;
		}

		private synchronized Entry requireAuthorizedEntry(String taskId,
				McpRequestContext requestContext) throws McpTaskNotFoundException {
			Entry entry = requireEntry(taskId);
			if (!entry.isAuthorized(requestContext))
				throw new McpTaskNotFoundException();
			return entry;
		}

		private static McpTask.Builder taskBuilder(String taskId,
				com.soklet.McpTaskOrigin taskOrigin, McpTaskStatus taskStatus,
				Instant createdAt, Instant lastUpdatedAt) {
			return McpTask.withTaskId(taskId, taskOrigin, taskStatus, createdAt,
					lastUpdatedAt)
					.timeToLive(TASK_TIME_TO_LIVE)
					.pollInterval(POLL_INTERVAL);
		}

		private static McpTask.Builder taskBuilder(McpTask task,
				McpTaskStatus taskStatus) {
			Instant lastUpdatedAt = Instant.now();
			if (lastUpdatedAt.isBefore(task.getLastUpdatedAt()))
				lastUpdatedAt = task.getLastUpdatedAt();
			return taskBuilder(task.getTaskId(), task.getTaskOrigin(), taskStatus,
					task.getCreatedAt(), lastUpdatedAt)
					.metadata(task.getMetadata());
		}

		private static void requireActive(McpTask task) {
			if (isTerminal(task.getTaskStatus()))
				throw new IllegalStateException("The task is already terminal.");
		}

		private void publishChanged(String taskId) {
			try {
				this.eventPublisher.publishTaskChanged(taskId);
			} catch (RuntimeException ignored) {
				// Polling remains authoritative for this fixture.
			}
		}

		private static boolean isTerminal(McpTaskStatus taskStatus) {
			return taskStatus == McpTaskStatus.COMPLETED
					|| taskStatus == McpTaskStatus.FAILED
					|| taskStatus == McpTaskStatus.CANCELED;
		}

		@FunctionalInterface
		private interface TaskTransition {
			void run() throws McpTaskNotFoundException;
		}

		private static final class Entry {
			private McpTask task;
			private final String endpointPath;
			private final Optional<String> authorizationPartitionKey;
			private boolean cancelationRequested;

			private Entry(McpTask task, String endpointPath,
					Optional<String> authorizationPartitionKey) {
				this.task = task;
				this.endpointPath = endpointPath;
				this.authorizationPartitionKey = authorizationPartitionKey;
			}

			private boolean isAuthorized(McpRequestContext requestContext) {
				return this.endpointPath.equals(requestContext.getEndpoint().getPath())
						&& this.authorizationPartitionKey.equals(requestContext
								.getAdmissionIdentity()
								.getAuthorizationPartitionKey());
			}
		}
	}
}
