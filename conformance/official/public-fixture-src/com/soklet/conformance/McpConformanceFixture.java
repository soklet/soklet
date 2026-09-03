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
import com.soklet.McpJsonObject;
import com.soklet.McpJsonRpcError;
import com.soklet.McpJsonRpcException;
import com.soklet.McpJsonString;
import com.soklet.McpLocalSubscriptionEventPublisher;
import com.soklet.McpOfficialSchemaConformanceTool;
import com.soklet.McpPromptArgumentDefinition;
import com.soklet.McpPromptMessage;
import com.soklet.McpPromptOutput;
import com.soklet.McpPromptRegistration;
import com.soklet.McpProgressReporter;
import com.soklet.McpProgressUpdate;
import com.soklet.McpProtectionConfig;
import com.soklet.McpProtectionKey;
import com.soklet.McpProtectionKeyRing;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpRateLimiter;
import com.soklet.McpAdmissionController;
import com.soklet.McpResourceContents;
import com.soklet.McpResourcePage;
import com.soklet.McpResourceRegistration;
import com.soklet.McpResourceOutput;
import com.soklet.McpRequestStateMode;
import com.soklet.McpServer;
import com.soklet.McpServerStatus;
import com.soklet.ShutdownComponentDisposition;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
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
	private static final McpProtectionConfig REQUEST_STATE_PROTECTION =
			McpProtectionConfig.withKeyRing(McpProtectionKeyRing.withActiveKey(
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
			"input-required-result-validate-input");

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
				.mcpServer(0,
						McpEndpointRegistry.fromEndpoints(List.of(endpoint)),
						McpAdmissionController.acceptAllInstance(),
						builder -> configureMcpServerForScenario(
								scenario, corsAuthorizer, builder))
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(lifecycleObserver)
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupCancelationTimeout(Duration.ofSeconds(1))
						.gracefulShutdownTimeout(Duration.ofSeconds(5))
						.forcedShutdownTimeout(Duration.ofSeconds(1))
						.build());
		if (metricsCollector != null)
			configured.metricsCollector(metricsCollector);
		return configured.build();
	}

	private static SokletConfig configForScenario(String scenario,
			CorsAuthorizer corsAuthorizer,
			LifecycleObserver lifecycleObserver) {
		McpServer mcpServer = mcpServerForScenario(scenario, corsAuthorizer,
				McpServer.withPort(0));
		return SokletConfig.withMcpServer(mcpServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(lifecycleObserver)
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupCancelationTimeout(Duration.ofSeconds(1))
						.gracefulShutdownTimeout(Duration.ofSeconds(5))
						.forcedShutdownTimeout(Duration.ofSeconds(1))
						.build())
				.build();
	}

	private static McpServer mcpServerForScenario(String scenario,
			CorsAuthorizer corsAuthorizer,
			McpServer.Builder mcpServerBuilder) {
		requireSupportedScenario(scenario);
		McpEndpoint endpoint = endpointForScenario(scenario);
		return configureMcpServerForScenario(scenario, corsAuthorizer,
				mcpServerBuilder)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.build();
	}

	private static McpServer.Builder configureMcpServerForScenario(
			String scenario, CorsAuthorizer corsAuthorizer,
			McpServer.Builder mcpServerBuilder) {
		requireSupportedScenario(scenario);
		McpRateLimiter allowLimiter = context ->
				McpRateLimitDecision.allowed();
		return mcpServerBuilder
				.host(LOOPBACK)
				.requestRateLimiter(allowLimiter)
				.toolRateLimiter(allowLimiter)
				.protectionConfig(REQUEST_STATE_PROTECTION)
				.corsAuthorizer(corsAuthorizer)
				.absentOriginPolicy(McpAbsentOriginPolicy.ALLOW)
				.allowedHosts(Set.of(LOOPBACK));
	}

	private static void requireSupportedScenario(String scenario) {
		if (!SUPPORTED_SCENARIOS.contains(scenario))
			throw new IllegalArgumentException(
					"Unsupported MCP conformance scenario: " + scenario);
	}

	static McpEndpoint endpointForScenario(String scenario) {
		McpEndpoint.Builder builder = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"soklet-public-conformance", "4.0.0")
						.description("Soklet MCP conformance fixture")
						.build())
				.includeServerInformation(true)
				.tools(tools(scenario))
				.prompts(prompts(scenario))
				.resources(resources())
				.resourceListHandler((request, list, features) -> {
					if (list.getCursor().isPresent())
						throw new McpJsonRpcException(
								McpJsonRpcError.fromInvalidParameters(
										"The resource-list cursor is invalid."));
					return McpResourcePage.builder()
							.resources(list.getRegisteredResourceDescriptors())
							.build();
				})
				.resourceListCachePolicy(CACHE_POLICY)
				.resourceTemplateListCachePolicy(CACHE_POLICY);
		if ("server-stateless".equals(scenario))
			builder.subscriptions(McpSubscriptionConfig.withEventPublisher(
					McpLocalSubscriptionEventPublisher.fromDefaults())
					.notificationType(
							McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED)
					.notificationType(
							McpSubscriptionNotificationType.RESOURCE_UPDATED)
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
						.jsonArguments()
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
					.jsonArguments()
					.handler((request, arguments, features) ->
							McpCompleteResult.fromToolText(
									"Sampling capability was declared."))
					.mayRequestInput(sampling)
					.description("Requires the base sampling capability.")
					.build());
			tools.add(McpToolRegistration.withName("test_streaming_elicitation")
					.jsonArguments()
					.handler((request, arguments, features) ->
							McpInputRequiredResult.builder()
									.inputRequest("conformance-value",
											McpInputRequest.fromDeclaration(
													elicitation,
													elicitationParameters))
									.build())
					.mayRequestInput(elicitation)
					.description("Returns one embedded elicitation input request.")
					.build());
			tools.add(rawTool("test_logging_tool",
					"Completes without emitting a log notification.",
					() -> McpCompleteResult.fromToolText(
							"No log notification was emitted.")));
		}
		addPhase5Tools(tools, scenario);
		return List.copyOf(tools);
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
				.jsonArguments()
				.handler((request, arguments, features) -> {
					if (request.getInputResponses().find("user_name").isPresent())
						return McpCompleteResult.fromToolText("Hello, Alice!");
					return McpInputRequiredResult.builder()
							.inputRequest("user_name", formInput(
									"What is your name?", "name", "string"))
							.build();
				})
				.mayRequestInput(FORM_INPUT)
				.description("Collects a user name through embedded elicitation.")
				.build();
	}

	private static McpToolRegistration<McpJsonObject> samplingTool() {
		return McpToolRegistration.withName(
				"test_input_required_result_sampling")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					if (request.getInputResponses().find(
							"capital_question").isPresent())
						return McpCompleteResult.fromToolText(
								"The capital of France is Paris.");
					return McpInputRequiredResult.builder()
							.inputRequest("capital_question", samplingInput(
									"What is the capital of France?", 100))
							.build();
				})
				.mayRequestInput(SAMPLING_INPUT)
				.description("Collects a sampling answer about France.")
				.build();
	}

	private static McpToolRegistration<McpJsonObject> listRootsTool() {
		return McpToolRegistration.withName(
				"test_input_required_result_list_roots")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					if (request.getInputResponses().find("client_roots").isPresent())
						return McpCompleteResult.fromToolText(
								"Client root file:///test/root accepted.");
					return McpInputRequiredResult.builder()
							.inputRequest("client_roots", rootsInput())
							.build();
				})
				.mayRequestInput(ROOTS_INPUT)
				.description("Collects the current client roots.")
				.build();
	}

	private static McpToolRegistration<McpJsonObject> requestStateTool() {
		return McpToolRegistration.withName(
				"test_input_required_result_request_state")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					if (hasFrameworkState(request, "request-state")
							&& request.getInputResponses().find("confirm").isPresent())
						return McpCompleteResult.fromToolText("state-ok");
					return McpInputRequiredResult.builder()
							.inputRequest("confirm", formInput(
									"Please confirm", "ok", "boolean"))
							.frameworkRequestState(McpJsonString.fromValue("request-state"))
							.build();
				})
				.mayRequestInput(FORM_INPUT)
				.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
				.description("Verifies protected request-state round trips.")
				.build();
	}

	private static McpToolRegistration<McpJsonObject> multipleInputsTool() {
		return McpToolRegistration.withName(
				"test_input_required_result_multiple_inputs")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					boolean complete = hasFrameworkState(request, "multiple-inputs")
							&& request.getInputResponses().find("user_name").isPresent()
							&& request.getInputResponses().find("greeting").isPresent()
							&& request.getInputResponses().find("client_roots").isPresent();
					if (complete)
						return McpCompleteResult.fromToolText(
								"All input responses accepted.");
					return McpInputRequiredResult.builder()
							.inputRequest("user_name", formInput(
									"What is your name?", "name", "string"))
							.inputRequest("greeting", samplingInput(
									"Generate a greeting", 50))
							.inputRequest("client_roots", rootsInput())
							.frameworkRequestState(McpJsonString.fromValue("multiple-inputs"))
							.build();
				})
				.mayRequestInput(FORM_INPUT, SAMPLING_INPUT, ROOTS_INPUT)
				.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
				.description("Collects elicitation, sampling, and roots responses.")
				.build();
	}

	private static McpToolRegistration<McpJsonObject> multiRoundTool() {
		return McpToolRegistration.withName(
				"test_input_required_result_multi_round")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					if (hasFrameworkState(request, "round-2")
							&& request.getInputResponses().find("step2").isPresent())
						return McpCompleteResult.fromToolText(
								"Multi-round input complete.");
					if (hasFrameworkState(request, "round-1")
							&& request.getInputResponses().find("step1").isPresent())
						return McpInputRequiredResult.builder()
								.inputRequest("step2", formInput(
										"Step 2: What is your favorite color?",
										"color", "string"))
								.frameworkRequestState(McpJsonString.fromValue("round-2"))
								.build();
					return McpInputRequiredResult.builder()
							.inputRequest("step1", formInput(
									"Step 1: What is your name?", "name", "string"))
							.frameworkRequestState(McpJsonString.fromValue("round-1"))
							.build();
				})
				.mayRequestInput(FORM_INPUT)
				.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
				.description("Collects input over two protected rounds.")
				.build();
	}

	private static McpToolRegistration<McpJsonObject> tamperedStateTool() {
		return McpToolRegistration.withName(
				"test_input_required_result_tampered_state")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					if (hasFrameworkState(request, "tamper-check")
							&& request.getInputResponses().find("confirm").isPresent())
						return McpCompleteResult.fromToolText(
								"Protected state accepted.");
					return McpInputRequiredResult.builder()
							.inputRequest("confirm", formInput(
									"Please confirm", "ok", "boolean"))
							.frameworkRequestState(McpJsonString.fromValue("tamper-check"))
							.build();
				})
				.mayRequestInput(FORM_INPUT)
				.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
				.description("Rejects modified protected request state.")
				.build();
	}

	private static McpToolRegistration<McpJsonObject> capabilityTool() {
		return McpToolRegistration.withName(
				"test_input_required_result_capabilities")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					if (request.getInputResponses().find("sampling").isPresent())
						return McpCompleteResult.fromToolText(
								"Sampling response accepted.");
					return McpInputRequiredResult.builder()
							.inputRequest("sampling", samplingInput(
									"Generate one supported response", 50))
							.build();
				})
				.mayRequestInput(SAMPLING_INPUT)
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
				.jsonArguments()
				.handler((request, arguments, features) -> resultSupplier.get())
				.description(description)
				.build();
	}

	private static McpCompleteResult mixedContentResult() {
		return McpCompleteResult.fromToolOutput(McpToolOutput.builder()
				.content(McpTextContent.fromText("Multiple content types test:"))
				.content(McpImageContent.withDataAndMimeType(
						PNG_BYTES, "image/png").build())
				.content(embeddedTextResource(
						URI.create("test://mixed-content-resource"),
						"application/json", "{\"test\":\"data\",\"value\":123}"))
				.build());
	}

	private static McpCompleteResult completeToolOutput(
			McpContentBlock content) {
		return McpCompleteResult.fromToolOutput(
				McpToolOutput.builder().content(content).build());
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
						.argument(requiredPromptArgument("arg1",
								"First test argument"))
						.argument(requiredPromptArgument("arg2",
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
						.argument(requiredPromptArgument("resourceUri",
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
						return McpInputRequiredResult.builder()
								.inputRequest("user_context", formInput(
										"What context should the prompt use?",
										"context", "string"))
								.build();
					})
					.mayRequestInput(FORM_INPUT)
					.description("Collects context before rendering a prompt.")
					.build());
		return List.copyOf(prompts);
	}

	private static McpPromptArgumentDefinition requiredPromptArgument(
			String name, String description) {
		return McpPromptArgumentDefinition.withName(name)
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
				McpResourceOutput.builder().content(contents).build());
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
			@McpHeader("Value") String value) {
	}
}
