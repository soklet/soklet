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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Black-box real-listener coverage for public MCP input-required results.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(30)
public class McpInputRequiredPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String ROOTS_CAPABILITY = "{\"roots\":{}}";
	private static final String FORM_AND_ROOTS_CAPABILITIES =
			"{\"elicitation\":{\"form\":{}},\"roots\":{}}";
	private static final String ALL_INPUT_CAPABILITIES =
			"{\"elicitation\":{\"form\":{},\"url\":{}},"
					+ "\"sampling\":{\"context\":{},\"tools\":{}},"
					+ "\"roots\":{}}";

	@Test
	public void declaredInputRequestsEmitExactWireForToolsPromptsAndResources()
			throws Exception {
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		McpInputRequestDeclaration form = McpInputRequestDeclaration
				.fromElicitationForm(McpInputRequirement.CONDITIONAL);
		McpInputRequestDeclaration roots = McpInputRequestDeclaration
				.fromRoots(McpInputRequirement.CONDITIONAL);
		McpJsonObject requestedSchema = McpJsonObject.builder()
				.put("type", "object")
				.put("properties", McpJsonObject.emptyInstance())
				.build();
		McpJsonObject toolFormParams = McpJsonObject.builder()
				.put("message", "Approve tool?")
				.put("mode", "form")
				.put("requestedSchema", requestedSchema)
				.build();
		McpJsonObject resourceFormParams = McpJsonObject.builder()
				.put("message", "Approve resource?")
				.put("mode", "form")
				.put("requestedSchema", requestedSchema)
				.build();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("input-tool")
				.jsonArguments()
				.handler((request, arguments, features) ->
						McpInputRequiredResult.builder()
								.inputRequest("approval", McpInputRequest.fromDeclaration(
										form, toolFormParams))
								.inputRequest("roots", McpInputRequest.fromDeclaration(
										roots,
												McpJsonObject.emptyInstance()))
								.metadata(McpJsonObject.builder()
										.put("testResult", "tool")
										.build())
								.build())
				.mayRequestInput(form, roots)
				.build();
		McpPromptRegistration prompt = McpPromptRegistration
				.withName("input-prompt")
				.handler((request, promptGet, features) ->
						McpInputRequiredResult.builder()
								.inputRequest("promptRoots", McpInputRequest.fromDeclaration(
										roots,
												McpJsonObject.emptyInstance()))
								.metadata(McpJsonObject.builder()
										.put("testResult", "prompt")
										.build())
								.build())
				.mayRequestInput(roots)
				.build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriTemplateAndName("test://items/{id}", "input-resource")
				.handler((request, read, features) ->
						McpInputRequiredResult.builder()
								.inputRequest("resourceApproval", McpInputRequest.fromDeclaration(
										form, resourceFormParams))
								.metadata(McpJsonObject.builder()
										.put("testResult", "resource")
										.build())
								.build())
				.mayRequestInput(form)
				.cachePolicy(McpCachePolicy.fromPublicTimeToLive(
						Duration.ofHours(1)))
				.build();
		McpEndpoint endpoint = endpointBuilder()
				.tool(tool)
				.prompt(prompt)
				.resource(resource)
				.build();
		McpServer server = server(endpoint,
				McpAdmissionController.acceptAllInstance(),
				context -> McpRateLimitDecision.allowed(),
				context -> McpRateLimitDecision.allowed(),
				(request, toolName, rawArguments, output) -> {
					sanitizerInvocations.incrementAndGet();
					return output;
				});

		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);

			HttpResponse<String> toolResponse = send(port, "tool-input",
					"tools/call", "input-tool",
					",\"name\":\"input-tool\",\"arguments\":{}",
					FORM_AND_ROOTS_CAPABILITIES);
			assertInputRequired(toolResponse, "tool-input");
			Assertions.assertEquals(
					"{\"jsonrpc\":\"2.0\",\"id\":\"tool-input\",\"result\":{"
							+ "\"inputRequests\":{"
							+ "\"approval\":{\"method\":\"elicitation/create\","
							+ "\"params\":{\"message\":\"Approve tool?\","
							+ "\"mode\":\"form\",\"requestedSchema\":{"
							+ "\"type\":\"object\",\"properties\":{}}}},"
							+ "\"roots\":{\"method\":\"roots/list\",\"params\":{}}},"
							+ "\"resultType\":\"input_required\","
							+ "\"_meta\":{\"testResult\":\"tool\"}}}",
					toolResponse.body());

			HttpResponse<String> promptResponse = send(port, "prompt-input",
					"prompts/get", "input-prompt",
					",\"name\":\"input-prompt\",\"arguments\":{}",
					FORM_AND_ROOTS_CAPABILITIES);
			assertInputRequired(promptResponse, "prompt-input");
			Assertions.assertEquals(
					"{\"jsonrpc\":\"2.0\",\"id\":\"prompt-input\",\"result\":{"
							+ "\"inputRequests\":{\"promptRoots\":{"
							+ "\"method\":\"roots/list\",\"params\":{}}},"
							+ "\"resultType\":\"input_required\","
							+ "\"_meta\":{\"testResult\":\"prompt\"}}}",
					promptResponse.body());

			HttpResponse<String> resourceResponse = send(port, "resource-input",
					"resources/read", "test://items/42",
					",\"uri\":\"test://items/42\"",
					FORM_AND_ROOTS_CAPABILITIES);
			assertInputRequired(resourceResponse, "resource-input");
			Assertions.assertEquals(
					"{\"jsonrpc\":\"2.0\",\"id\":\"resource-input\",\"result\":{"
							+ "\"inputRequests\":{\"resourceApproval\":{"
							+ "\"method\":\"elicitation/create\",\"params\":{"
							+ "\"message\":\"Approve resource?\",\"mode\":\"form\","
							+ "\"requestedSchema\":{\"type\":\"object\","
							+ "\"properties\":{}}}}},\"resultType\":\"input_required\","
							+ "\"_meta\":{\"testResult\":\"resource\"}}}",
					resourceResponse.body());
			Assertions.assertFalse(resourceResponse.body().contains("\"ttlMs\""),
					resourceResponse.body());
			Assertions.assertFalse(
					resourceResponse.body().contains("\"cacheScope\""),
					resourceResponse.body());
			Assertions.assertEquals(0, sanitizerInvocations.get());
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void validMethodSpecificParametersPreserveExactOpenWire()
			throws Exception {
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		McpInputRequestDeclaration form = McpInputRequestDeclaration
				.fromElicitationForm(McpInputRequirement.CONDITIONAL);
		McpInputRequestDeclaration url = McpInputRequestDeclaration
				.fromElicitationUrl(McpInputRequirement.CONDITIONAL);
		McpInputRequestDeclaration sampling = McpInputRequestDeclaration
				.fromSampling(Set.of(McpClientCapability.SAMPLING_CONTEXT,
						McpClientCapability.SAMPLING_TOOLS),
						McpInputRequirement.CONDITIONAL);
		McpJsonObject formParams = McpJsonObject.builder()
				.put("message", "Approve without a mode?")
				.put("requestedSchema", McpJsonObject.builder()
						.put("type", "object")
						.put("properties", McpJsonObject.builder()
								.put("approved", McpJsonObject.builder()
										.put("type", "boolean")
										.put("x-primitive-extension", true)
										.build())
								.build())
						.build())
				.put("x-form-extension", "preserved")
				.build();
		McpJsonObject urlParams = McpJsonObject.builder()
				.put("message", "Authorize externally")
				.put("mode", "url")
				.put("url", "https://example.com/authorize?state=abc")
				.put("x-url-extension", 42)
				.build();
		McpJsonObject samplingParams = McpJsonObject.builder()
				.put("messages", McpJsonArray.builder()
						.add(McpJsonObject.builder()
								.put("role", "user")
								.put("content", McpJsonObject.builder()
										.put("type", "text")
										.put("text", "Use the lookup tool")
										.build())
								.build())
						.build())
				.put("maxTokens", 64)
				.put("includeContext", "allServers")
				.put("modelPreferences", McpJsonObject.builder()
						.put("costPriority", 0.25)
						.put("speedPriority", 1)
						.put("hints", McpJsonArray.builder()
								.add(McpJsonObject.builder()
										.put("name", "small")
										.put("x-hint-extension", true)
										.build())
								.build())
						.build())
				.put("tools", McpJsonArray.builder()
						.add(McpJsonObject.builder()
								.put("name", "lookup")
								.put("inputSchema", McpJsonObject.builder()
										.put("type", "object")
										.put("properties", McpJsonObject.emptyInstance())
										.build())
								.build())
						.build())
				.put("toolChoice", McpJsonObject.builder()
						.put("mode", "required")
						.build())
				.put("x-sampling-extension", "preserved")
				.build();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("valid-form-input")
				.jsonArguments()
				.handler((request, arguments, features) -> inputRequired(
						"form", form, formParams))
				.mayRequestInput(form)
				.build();
		McpPromptRegistration prompt = McpPromptRegistration
				.withName("valid-url-input")
				.handler((request, promptGet, features) -> inputRequired(
						"url", url, urlParams))
				.mayRequestInput(url)
				.build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(URI.create("test://valid-sampling"),
						"valid-sampling-input")
				.handler((request, read, features) -> inputRequired(
						"sampling", sampling, samplingParams))
				.mayRequestInput(sampling)
				.cachePolicy(McpCachePolicy.fromPublicTimeToLive(
						Duration.ofHours(1)))
				.build();
		McpEndpoint endpoint = endpointBuilder()
				.tool(tool)
				.prompt(prompt)
				.resource(resource)
				.build();
		McpServer server = server(endpoint,
				McpAdmissionController.acceptAllInstance(),
				context -> McpRateLimitDecision.allowed(),
				context -> McpRateLimitDecision.allowed(),
				(request, toolName, rawArguments, output) -> {
					sanitizerInvocations.incrementAndGet();
					return output;
				});

		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);

			HttpResponse<String> formResponse = callTool(port,
					"valid-form", "valid-form-input", ALL_INPUT_CAPABILITIES);
			assertInputRequired(formResponse, "valid-form");
			Assertions.assertEquals(
					"{\"jsonrpc\":\"2.0\",\"id\":\"valid-form\",\"result\":{"
							+ "\"inputRequests\":{\"form\":{"
							+ "\"method\":\"elicitation/create\",\"params\":{"
							+ "\"message\":\"Approve without a mode?\","
							+ "\"requestedSchema\":{\"type\":\"object\","
							+ "\"properties\":{\"approved\":{\"type\":\"boolean\","
							+ "\"x-primitive-extension\":true}}},"
							+ "\"x-form-extension\":\"preserved\"}}},"
							+ "\"resultType\":\"input_required\"}}",
					formResponse.body());

			HttpResponse<String> urlResponse = send(port, "valid-url",
					"prompts/get", "valid-url-input",
					",\"name\":\"valid-url-input\",\"arguments\":{}",
					ALL_INPUT_CAPABILITIES);
			assertInputRequired(urlResponse, "valid-url");
			Assertions.assertEquals(
					"{\"jsonrpc\":\"2.0\",\"id\":\"valid-url\",\"result\":{"
							+ "\"inputRequests\":{\"url\":{"
							+ "\"method\":\"elicitation/create\",\"params\":{"
							+ "\"message\":\"Authorize externally\",\"mode\":\"url\","
							+ "\"url\":\"https://example.com/authorize?state=abc\","
							+ "\"x-url-extension\":42}}},"
							+ "\"resultType\":\"input_required\"}}",
					urlResponse.body());

			HttpResponse<String> samplingResponse = send(port, "valid-sampling",
					"resources/read", "test://valid-sampling",
					",\"uri\":\"test://valid-sampling\"",
					ALL_INPUT_CAPABILITIES);
			assertInputRequired(samplingResponse, "valid-sampling");
			Assertions.assertEquals(
					"{\"jsonrpc\":\"2.0\",\"id\":\"valid-sampling\",\"result\":{"
							+ "\"inputRequests\":{\"sampling\":{"
							+ "\"method\":\"sampling/createMessage\",\"params\":{"
							+ "\"messages\":[{\"role\":\"user\",\"content\":{"
							+ "\"type\":\"text\",\"text\":\"Use the lookup tool\"}}],"
							+ "\"maxTokens\":64,\"includeContext\":\"allServers\","
							+ "\"modelPreferences\":{\"costPriority\":0.25,"
							+ "\"speedPriority\":1,\"hints\":[{\"name\":\"small\","
							+ "\"x-hint-extension\":true}]},\"tools\":[{"
							+ "\"name\":\"lookup\",\"inputSchema\":{\"type\":\"object\","
							+ "\"properties\":{}}}],\"toolChoice\":{\"mode\":\"required\"},"
							+ "\"x-sampling-extension\":\"preserved\"}}},"
							+ "\"resultType\":\"input_required\"}}",
					samplingResponse.body());
			Assertions.assertFalse(samplingResponse.body().contains("\"ttlMs\""),
					samplingResponse.body());
			Assertions.assertFalse(
					samplingResponse.body().contains("\"cacheScope\""),
					samplingResponse.body());
			Assertions.assertEquals(0, sanitizerInvocations.get());
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void invalidMethodSpecificParametersFailClosedAcrossOperationKinds()
			throws Exception {
		String formSecret = "INVALID-FORM-PARAMETER-SECRET";
		String samplingSecret = "INVALID-SAMPLING-PARAMETER-SECRET";
		String rootsSecret = "INVALID-ROOTS-PARAMETER-SECRET";
		String metadataSecret = "INVALID-METADATA-SECRET";
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		McpInputRequestDeclaration form = McpInputRequestDeclaration
				.fromElicitationForm(McpInputRequirement.CONDITIONAL);
		McpInputRequestDeclaration sampling = McpInputRequestDeclaration
				.fromSampling(Set.of(), McpInputRequirement.CONDITIONAL);
		McpInputRequestDeclaration roots = McpInputRequestDeclaration
				.fromRoots(McpInputRequirement.CONDITIONAL);
		McpJsonObject requestedSchema = McpJsonObject.builder()
				.put("type", "object")
				.put("properties", McpJsonObject.emptyInstance())
				.build();
		McpJsonObject validFormParams = McpJsonObject.builder()
				.put("message", "Valid first request")
				.put("requestedSchema", requestedSchema)
				.build();
		McpJsonObject invalidFormParams = McpJsonObject.builder()
				.put("message", formSecret)
				.put("mode", "url")
				.put("requestedSchema", requestedSchema)
				.build();
		McpJsonObject invalidSamplingParams = McpJsonObject.builder()
				.put("messages", McpJsonArray.builder()
						.add(McpJsonObject.builder()
								.put("role", "user")
								.put("content", McpJsonObject.builder()
										.put("type", "text")
										.put("text", "Invalid maxTokens")
										.build())
								.build())
						.build())
				.put("maxTokens", samplingSecret)
				.build();
		McpJsonObject invalidRootsParams = McpJsonObject.builder()
				.put("_meta", rootsSecret)
				.build();
		McpJsonObject secretMetadata = McpJsonObject.builder()
				.put("secret", metadataSecret)
				.build();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("invalid-form-input")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					return McpInputRequiredResult.builder()
							.inputRequest("valid-first", McpInputRequest.fromDeclaration(
									roots,
											McpJsonObject.emptyInstance()))
							.inputRequest("invalid-form", McpInputRequest.fromDeclaration(
									form, invalidFormParams))
							.metadata(secretMetadata)
							.build();
				})
				.mayRequestInput(roots, form)
				.build();
		McpPromptRegistration prompt = McpPromptRegistration
				.withName("invalid-sampling-input")
				.handler((request, promptGet, features) -> {
					handlerInvocations.incrementAndGet();
					return McpInputRequiredResult.builder()
							.inputRequest("valid-first", McpInputRequest.fromDeclaration(
									form, validFormParams))
							.inputRequest("invalid-sampling", McpInputRequest.fromDeclaration(
									sampling,
											invalidSamplingParams))
							.metadata(secretMetadata)
							.build();
				})
				.mayRequestInput(form, sampling)
				.build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(URI.create("test://invalid-roots"),
						"invalid-roots-input")
				.handler((request, read, features) -> {
					handlerInvocations.incrementAndGet();
					return McpInputRequiredResult.builder()
							.inputRequest("valid-first", McpInputRequest.fromDeclaration(
									form, validFormParams))
							.inputRequest("invalid-roots", McpInputRequest.fromDeclaration(
									roots, invalidRootsParams))
							.metadata(secretMetadata)
							.build();
				})
				.mayRequestInput(form, roots)
				.cachePolicy(McpCachePolicy.fromPublicTimeToLive(
						Duration.ofHours(1)))
				.build();
		McpEndpoint endpoint = endpointBuilder()
				.tool(tool)
				.prompt(prompt)
				.resource(resource)
				.build();
		McpServer server = server(endpoint,
				McpAdmissionController.acceptAllInstance(),
				context -> McpRateLimitDecision.allowed(),
				context -> McpRateLimitDecision.allowed(),
				(request, toolName, rawArguments, output) -> {
					sanitizerInvocations.incrementAndGet();
					return output;
				});

		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);
			HttpResponse<String> toolResponse = callTool(port,
					"invalid-form", "invalid-form-input", ALL_INPUT_CAPABILITIES);
			assertInternalErrorWithoutOutput(toolResponse, "invalid-form",
					formSecret, metadataSecret);

			HttpResponse<String> promptResponse = send(port, "invalid-sampling",
					"prompts/get", "invalid-sampling-input",
					",\"name\":\"invalid-sampling-input\",\"arguments\":{}",
					ALL_INPUT_CAPABILITIES);
			assertInternalErrorWithoutOutput(promptResponse, "invalid-sampling",
					samplingSecret, metadataSecret);

			HttpResponse<String> resourceResponse = send(port, "invalid-roots",
					"resources/read", "test://invalid-roots",
					",\"uri\":\"test://invalid-roots\"",
					ALL_INPUT_CAPABILITIES);
			assertInternalErrorWithoutOutput(resourceResponse, "invalid-roots",
					rootsSecret, metadataSecret);
			Assertions.assertFalse(resourceResponse.body().contains("\"ttlMs\""),
					resourceResponse.body());
			Assertions.assertFalse(
					resourceResponse.body().contains("\"cacheScope\""),
					resourceResponse.body());
			Assertions.assertEquals(3, handlerInvocations.get());
			Assertions.assertEquals(0, sanitizerInvocations.get());
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void capabilityRequirementsRespectThePolicyBoundaryAndRequestScope()
			throws Exception {
		AtomicInteger admissionInvocations = new AtomicInteger();
		AtomicInteger requestLimiterInvocations = new AtomicInteger();
		AtomicInteger toolLimiterInvocations = new AtomicInteger();
		AtomicInteger requiredHandlerInvocations = new AtomicInteger();
		AtomicInteger conditionalCompleteHandlerInvocations = new AtomicInteger();
		AtomicInteger conditionalInputHandlerInvocations = new AtomicInteger();
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		McpInputRequestDeclaration requiredRoots = McpInputRequestDeclaration
				.fromRoots(McpInputRequirement.REQUIRED);
		McpInputRequestDeclaration conditionalRoots = McpInputRequestDeclaration
				.fromRoots(McpInputRequirement.CONDITIONAL);
		McpToolRegistration<McpJsonObject> required = McpToolRegistration
				.withName("required-roots")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					requiredHandlerInvocations.incrementAndGet();
					return inputRequired("roots", requiredRoots,
							McpJsonObject.emptyInstance());
				})
				.mayRequestInput(requiredRoots)
				.build();
		McpToolRegistration<McpJsonObject> conditionalComplete =
				McpToolRegistration.withName("conditional-complete")
						.jsonArguments()
						.handler((request, arguments, features) -> {
							conditionalCompleteHandlerInvocations.incrementAndGet();
							return McpCompleteResult.fromToolText("complete");
						})
						.mayRequestInput(conditionalRoots)
						.build();
		McpToolRegistration<McpJsonObject> conditionalInput = McpToolRegistration
				.withName("conditional-input")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					conditionalInputHandlerInvocations.incrementAndGet();
					return inputRequired("roots", conditionalRoots,
							McpJsonObject.emptyInstance());
				})
				.mayRequestInput(conditionalRoots)
				.build();
		McpEndpoint endpoint = endpointBuilder()
				.tools(List.of(required, conditionalComplete, conditionalInput))
				.build();
		McpServer server = server(endpoint, context -> {
			admissionInvocations.incrementAndGet();
			return McpAdmissionDecision.accepted();
		}, context -> {
			requestLimiterInvocations.incrementAndGet();
			return McpRateLimitDecision.allowed();
		}, context -> {
			toolLimiterInvocations.incrementAndGet();
			return McpRateLimitDecision.allowed();
		}, (request, toolName, rawArguments, output) -> {
			sanitizerInvocations.incrementAndGet();
			return output;
		});

		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);

			HttpResponse<String> missingRequired = callTool(port,
					"missing-required", "required-roots", "{}");
			assertMissingCapability(missingRequired, "missing-required",
					ROOTS_CAPABILITY);
			assertCounts(0, admissionInvocations, requestLimiterInvocations,
					toolLimiterInvocations, requiredHandlerInvocations,
					conditionalCompleteHandlerInvocations,
					conditionalInputHandlerInvocations, sanitizerInvocations);

			HttpResponse<String> supportedRequired = callTool(port,
					"supported-required", "required-roots", ROOTS_CAPABILITY);
			assertInputRequired(supportedRequired, "supported-required");
			Assertions.assertEquals(1, admissionInvocations.get());
			Assertions.assertEquals(1, requestLimiterInvocations.get());
			Assertions.assertEquals(1, toolLimiterInvocations.get());
			Assertions.assertEquals(1, requiredHandlerInvocations.get());
			Assertions.assertEquals(0, sanitizerInvocations.get());

			HttpResponse<String> completeWithoutCapability = callTool(port,
					"conditional-complete", "conditional-complete", "{}");
			assertComplete(completeWithoutCapability, "conditional-complete");
			Assertions.assertEquals(1,
					conditionalCompleteHandlerInvocations.get());
			Assertions.assertEquals(1, sanitizerInvocations.get());

			HttpResponse<String> emittedWithoutCapability = callTool(port,
					"conditional-missing", "conditional-input", "{}");
			assertMissingCapability(emittedWithoutCapability,
					"conditional-missing", ROOTS_CAPABILITY);
			Assertions.assertEquals(1, conditionalInputHandlerInvocations.get());
			Assertions.assertEquals(1, sanitizerInvocations.get());

			HttpResponse<String> emittedWithCapability = callTool(port,
					"conditional-supported", "conditional-input",
					ROOTS_CAPABILITY);
			assertInputRequired(emittedWithCapability, "conditional-supported");
			Assertions.assertEquals(4, admissionInvocations.get());
			Assertions.assertEquals(4, requestLimiterInvocations.get());
			Assertions.assertEquals(4, toolLimiterInvocations.get());
			Assertions.assertEquals(2, conditionalInputHandlerInvocations.get());
			Assertions.assertEquals(1, sanitizerInvocations.get());
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void missingCapabilityWinsOverMalformedSecretBearingOutputAndRemainsRequestScoped()
			throws Exception {
		String parameterSecret = "MISSING-CAPABILITY-PARAMETER-SECRET";
		String metadataSecret = "MISSING-CAPABILITY-METADATA-SECRET";
		String inputKeySecret = "MISSING-CAPABILITY-INPUT-KEY-SECRET";
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		McpInputRequestDeclaration roots = McpInputRequestDeclaration
					.fromRoots(McpInputRequirement.CONDITIONAL);
		McpInputRequestDeclaration undeclaredRoots = McpInputRequestDeclaration
					.fromRoots(McpInputRequirement.REQUIRED);
		McpJsonObject invalidRootsParams = McpJsonObject.builder()
				.put("_meta", parameterSecret)
				.put("secret", parameterSecret)
				.build();
		McpJsonObject secretMetadata = McpJsonObject.builder()
				.put("secret", metadataSecret)
				.build();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("conditional-malformed-input")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					return McpInputRequiredResult.builder()
							.inputRequest("undeclared-" + inputKeySecret,
									McpInputRequest.fromDeclaration(undeclaredRoots,
											McpJsonObject.emptyInstance()))
							.inputRequest(inputKeySecret, McpInputRequest.fromDeclaration(
									roots, invalidRootsParams))
							.metadata(secretMetadata)
							.build();
				})
				.mayRequestInput(roots)
				.build();
		McpEndpoint endpoint = endpointBuilder().tool(tool).build();
		McpServer server = server(endpoint,
				McpAdmissionController.acceptAllInstance(),
				context -> McpRateLimitDecision.allowed(),
				context -> McpRateLimitDecision.allowed(),
				(request, toolName, rawArguments, output) -> {
					sanitizerInvocations.incrementAndGet();
					return output;
				});

		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);
			HttpResponse<String> missingCapability = callTool(port,
					"missing-before-malformed", "conditional-malformed-input", "{}");

			assertMissingCapability(missingCapability,
					"missing-before-malformed", ROOTS_CAPABILITY);
			Assertions.assertFalse(missingCapability.body().contains(parameterSecret),
					missingCapability.body());
			Assertions.assertFalse(missingCapability.body().contains(metadataSecret),
					missingCapability.body());
			Assertions.assertFalse(missingCapability.body().contains(inputKeySecret),
					missingCapability.body());
			Assertions.assertFalse(missingCapability.body().contains("inputRequests"),
					missingCapability.body());
			Assertions.assertFalse(missingCapability.body().contains("\"result\""),
					missingCapability.body());
			Assertions.assertEquals(1, handlerInvocations.get());
			Assertions.assertEquals(0, sanitizerInvocations.get());

			HttpResponse<String> supportedButMalformed = callTool(port,
					"supported-malformed", "conditional-malformed-input",
					ROOTS_CAPABILITY);
			assertInternalErrorWithoutOutput(supportedButMalformed,
					"supported-malformed", parameterSecret, metadataSecret);
			Assertions.assertFalse(supportedButMalformed.body().contains(inputKeySecret),
					supportedButMalformed.body());
			Assertions.assertEquals(2, handlerInvocations.get());
			Assertions.assertEquals(0, sanitizerInvocations.get());
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void undeclaredInputRequestsFailClosedWithoutSanitizationOrLeaks()
			throws Exception {
		String parameterSecret = "UNDECLARED-PARAMETER-SECRET";
		String metadataSecret = "UNDECLARED-METADATA-SECRET";
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		McpInputRequestDeclaration declared = McpInputRequestDeclaration
				.fromElicitationForm(McpInputRequirement.CONDITIONAL);
		McpInputRequestDeclaration emitted = McpInputRequestDeclaration
				.fromRoots(McpInputRequirement.CONDITIONAL);
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("undeclared-input")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					return McpInputRequiredResult.builder()
							.inputRequest("secret-key", McpInputRequest.fromDeclaration(
									emitted,
											McpJsonObject.builder()
													.put("secret", parameterSecret)
													.build()))
							.metadata(McpJsonObject.builder()
									.put("secret", metadataSecret)
									.build())
							.build();
				})
				.mayRequestInput(declared)
				.build();
		McpEndpoint endpoint = endpointBuilder().tool(tool).build();
		McpServer server = server(endpoint,
				McpAdmissionController.acceptAllInstance(),
				context -> McpRateLimitDecision.allowed(),
				context -> McpRateLimitDecision.allowed(),
				(request, toolName, rawArguments, output) -> {
					sanitizerInvocations.incrementAndGet();
					return output;
				});

		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			HttpResponse<String> response = callTool(boundPort(server),
					"undeclared", "undeclared-input",
					FORM_AND_ROOTS_CAPABILITIES);

			Assertions.assertEquals(500, response.statusCode(), response.body());
			Assertions.assertEquals(
					"{\"jsonrpc\":\"2.0\",\"id\":\"undeclared\",\"error\":{"
							+ "\"code\":-32603,\"message\":\"Internal error\"}}",
					response.body());
			Assertions.assertFalse(response.body().contains(parameterSecret),
					response.body());
			Assertions.assertFalse(response.body().contains(metadataSecret),
					response.body());
			Assertions.assertFalse(response.body().contains("secret-key"),
					response.body());
			Assertions.assertEquals(1, handlerInvocations.get());
			Assertions.assertEquals(0, sanitizerInvocations.get());
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void interceptorShortCircuitsRejectUndeclaredInputRequestsAcrossOperationKinds()
			throws Exception {
		String inputKeySecret = "INTERCEPTOR-UNDECLARED-INPUT-KEY-SECRET";
		String parameterSecret = "INTERCEPTOR-UNDECLARED-PARAMETER-SECRET";
		String metadataSecret = "INTERCEPTOR-UNDECLARED-METADATA-SECRET";
		AtomicInteger interceptorInvocations = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		McpInputRequestDeclaration declared = McpInputRequestDeclaration
				.fromElicitationForm(McpInputRequirement.CONDITIONAL);
		McpInputRequestDeclaration emitted = McpInputRequestDeclaration
				.fromRoots(McpInputRequirement.CONDITIONAL);
		McpInputRequiredResult undeclaredResult = McpInputRequiredResult.builder()
				.inputRequest(inputKeySecret, McpInputRequest.fromDeclaration(
						emitted, McpJsonObject.builder()
								.put("x-secret", parameterSecret)
								.build()))
				.metadata(McpJsonObject.builder()
						.put("secret", metadataSecret)
						.build())
				.build();
		URI resourceUri = URI.create("test://interceptor-undeclared-input");
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("interceptor-undeclared-tool")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText("must-not-run");
				})
				.mayRequestInput(declared)
				.build();
		McpPromptRegistration prompt = McpPromptRegistration
				.withName("interceptor-undeclared-prompt")
				.handler((request, promptGet, features) -> {
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromPromptOutput(
							McpPromptOutput.fromMessages(
									McpPromptMessage.fromUserContent(
											McpTextContent.fromText("must-not-run"))));
				})
				.mayRequestInput(declared)
				.build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(resourceUri, "interceptor-undeclared-resource")
				.handler((request, read, features) -> {
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromResourceOutput(
							McpResourceOutput.builder()
									.content(McpTextResourceContents.withUriAndText(
											read.getUri(), "must-not-run")
											.build())
									.build());
				})
				.mayRequestInput(declared)
				.cachePolicy(McpCachePolicy.fromPublicTimeToLive(
						Duration.ofHours(1)))
				.build();
		McpEndpoint endpoint = endpointBuilder()
				.tool(tool)
				.prompt(prompt)
				.resource(resource)
				.build();
		McpServer server = server(endpoint,
				McpAdmissionController.acceptAllInstance(),
				context -> McpRateLimitDecision.allowed(),
				context -> McpRateLimitDecision.allowed(),
				(context, continuation) -> {
					interceptorInvocations.incrementAndGet();
					return undeclaredResult;
				},
				(request, toolName, rawArguments, output) -> {
					sanitizerInvocations.incrementAndGet();
					return output;
				});

		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);
			HttpResponse<String> toolResponse = callTool(port,
					"interceptor-undeclared-tool-response",
					"interceptor-undeclared-tool",
					FORM_AND_ROOTS_CAPABILITIES);
			assertInternalErrorWithoutOutput(toolResponse,
					"interceptor-undeclared-tool-response",
					parameterSecret, metadataSecret);
			Assertions.assertFalse(toolResponse.body().contains(inputKeySecret),
					toolResponse.body());

			HttpResponse<String> promptResponse = send(port,
					"interceptor-undeclared-prompt-response", "prompts/get",
					"interceptor-undeclared-prompt",
					",\"name\":\"interceptor-undeclared-prompt\",\"arguments\":{}",
					FORM_AND_ROOTS_CAPABILITIES);
			assertInternalErrorWithoutOutput(promptResponse,
					"interceptor-undeclared-prompt-response",
					parameterSecret, metadataSecret);
			Assertions.assertFalse(promptResponse.body().contains(inputKeySecret),
					promptResponse.body());

			HttpResponse<String> resourceResponse = send(port,
					"interceptor-undeclared-resource-response", "resources/read",
					resourceUri.toString(), ",\"uri\":\"" + resourceUri + "\"",
					FORM_AND_ROOTS_CAPABILITIES);
			assertInternalErrorWithoutOutput(resourceResponse,
					"interceptor-undeclared-resource-response",
					parameterSecret, metadataSecret);
			Assertions.assertFalse(resourceResponse.body().contains(inputKeySecret),
					resourceResponse.body());
			Assertions.assertFalse(resourceResponse.body().contains("\"ttlMs\""),
					resourceResponse.body());
			Assertions.assertFalse(
					resourceResponse.body().contains("\"cacheScope\""),
					resourceResponse.body());
			Assertions.assertEquals(3, interceptorInvocations.get());
			Assertions.assertEquals(0, handlerInvocations.get());
			Assertions.assertEquals(0, sanitizerInvocations.get());
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void toolInterceptorInputRequiredResultsHonorValidationAndCapabilityPrecedence()
			throws Exception {
		String inputKeySecret = "TOOL-INTERCEPTOR-INPUT-KEY-SECRET";
		String parameterSecret = "TOOL-INTERCEPTOR-PARAMETER-SECRET";
		String metadataSecret = "TOOL-INTERCEPTOR-METADATA-SECRET";
		AtomicInteger interceptorInvocations = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		McpInputRequestDeclaration form = McpInputRequestDeclaration
				.fromElicitationForm(McpInputRequirement.CONDITIONAL);
		McpInputRequestDeclaration roots = McpInputRequestDeclaration
				.fromRoots(McpInputRequirement.CONDITIONAL);
		McpJsonObject invalidRootsParams = McpJsonObject.builder()
				.put("_meta", parameterSecret)
				.put("secret", parameterSecret)
				.build();
		McpJsonObject secretMetadata = McpJsonObject.builder()
				.put("secret", metadataSecret)
				.build();
		McpEndpoint.Builder endpointBuilder = endpointBuilder();
		for (String toolName : List.of("interceptor-valid-input",
				"interceptor-undeclared-input", "interceptor-invalid-input",
				"interceptor-missing-capability")) {
			McpToolRegistration.Builder<McpJsonObject> toolBuilder =
					McpToolRegistration.withName(toolName)
							.jsonArguments()
							.handler((request, arguments, features) -> {
								handlerInvocations.incrementAndGet();
								return McpCompleteResult.fromToolText("must-not-run");
							});
			if (toolName.equals("interceptor-undeclared-input")
					|| toolName.equals("interceptor-missing-capability"))
				toolBuilder.mayRequestInput(form);
			else
				toolBuilder.mayRequestInput(roots);
			endpointBuilder.tool(toolBuilder.build());
		}
		McpServer server = server(endpointBuilder.build(),
				McpAdmissionController.acceptAllInstance(),
				context -> McpRateLimitDecision.allowed(),
				context -> McpRateLimitDecision.allowed(),
				(context, continuation) -> {
					interceptorInvocations.incrementAndGet();
					return switch (context.getOperationName().orElseThrow()) {
						case "interceptor-valid-input" -> inputRequired(
								"valid", roots, McpJsonObject.emptyInstance());
						case "interceptor-undeclared-input" ->
								McpInputRequiredResult.builder()
										.inputRequest(inputKeySecret,
												McpInputRequest.fromDeclaration(roots,
														McpJsonObject.emptyInstance()))
										.metadata(secretMetadata)
										.build();
						case "interceptor-invalid-input",
								"interceptor-missing-capability" ->
								McpInputRequiredResult.builder()
										.inputRequest(inputKeySecret,
												McpInputRequest.fromDeclaration(roots,
														invalidRootsParams))
										.metadata(secretMetadata)
										.build();
						default -> continuation.proceed();
					};
				},
				(request, toolName, rawArguments, output) -> {
					sanitizerInvocations.incrementAndGet();
					return output;
				});

		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);
			HttpResponse<String> valid = callTool(port, "interceptor-valid",
					"interceptor-valid-input", ROOTS_CAPABILITY);
			assertInputRequired(valid, "interceptor-valid");
			Assertions.assertEquals(
					"{\"jsonrpc\":\"2.0\",\"id\":\"interceptor-valid\",\"result\":{"
							+ "\"inputRequests\":{\"valid\":{"
							+ "\"method\":\"roots/list\",\"params\":{}}},"
							+ "\"resultType\":\"input_required\"}}",
					valid.body());
			Assertions.assertEquals(0, sanitizerInvocations.get());

			HttpResponse<String> undeclared = callTool(port,
					"interceptor-undeclared", "interceptor-undeclared-input",
					FORM_AND_ROOTS_CAPABILITIES);
			assertInternalErrorWithoutOutput(undeclared,
					"interceptor-undeclared", inputKeySecret, metadataSecret);

			HttpResponse<String> invalid = callTool(port, "interceptor-invalid",
					"interceptor-invalid-input", ROOTS_CAPABILITY);
			assertInternalErrorWithoutOutput(invalid, "interceptor-invalid",
					parameterSecret, metadataSecret);
			Assertions.assertFalse(invalid.body().contains(inputKeySecret),
					invalid.body());

			HttpResponse<String> missingCapability = callTool(port,
					"interceptor-missing", "interceptor-missing-capability", "{}");
			assertMissingCapability(missingCapability, "interceptor-missing",
					ROOTS_CAPABILITY);
			Assertions.assertFalse(
					missingCapability.body().contains(inputKeySecret),
					missingCapability.body());
			Assertions.assertFalse(
					missingCapability.body().contains(parameterSecret),
					missingCapability.body());
			Assertions.assertFalse(
					missingCapability.body().contains(metadataSecret),
					missingCapability.body());
			Assertions.assertFalse(
					missingCapability.body().contains("inputRequests"),
					missingCapability.body());
			Assertions.assertFalse(missingCapability.body().contains("\"result\""),
					missingCapability.body());
			Assertions.assertEquals(4, interceptorInvocations.get());
			Assertions.assertEquals(0, handlerInvocations.get());
			Assertions.assertEquals(0, sanitizerInvocations.get());
		} finally {
			soklet.stop();
		}
	}

	private static McpEndpoint.Builder endpointBuilder() {
		return McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"input-required-public-runtime-test",
						"4.0.0-SNAPSHOT").build());
	}

	private static Soklet managedSoklet(McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.build());
	}

	private static McpServer server(McpEndpoint endpoint,
			McpAdmissionController admissionController,
			McpRateLimiter requestRateLimiter,
			McpRateLimiter toolRateLimiter,
			McpToolOutputSanitizer sanitizer) {
		return server(endpoint, admissionController, requestRateLimiter,
				toolRateLimiter, McpHandlerInterceptor.passThroughInstance(),
				sanitizer);
	}

	private static McpServer server(McpEndpoint endpoint,
			McpAdmissionController admissionController,
			McpRateLimiter requestRateLimiter,
			McpRateLimiter toolRateLimiter,
			McpHandlerInterceptor handlerInterceptor,
			McpToolOutputSanitizer sanitizer) {
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(
						McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(admissionController)
				.requestRateLimiter(requestRateLimiter)
				.toolRateLimiter(toolRateLimiter)
				.handlerInterceptor(handlerInterceptor)
				.toolOutputSanitizer(sanitizer)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
	}

	private static int boundPort(McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	private static McpInputRequiredResult inputRequired(String key,
			McpInputRequestDeclaration declaration, McpJsonObject params) {
		return McpInputRequiredResult.builder()
				.inputRequest(key, McpInputRequest.fromDeclaration(declaration, params))
				.build();
	}

	private static HttpResponse<String> callTool(int port, String requestId,
			String toolName, String clientCapabilities) throws Exception {
		return send(port, requestId, "tools/call", toolName,
				",\"name\":\"" + toolName + "\",\"arguments\":{}",
				clientCapabilities);
	}

	private static HttpResponse<String> send(int port, String requestId,
			String method, String operationName, String additionalParameters,
			String clientCapabilities) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":"
				+ clientCapabilities + "}" + additionalParameters + "}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method)
				.header("Mcp-Name", operationName)
				.POST(HttpRequest.BodyPublishers.ofString(
						body, StandardCharsets.UTF_8))
				.build();
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request, HttpResponse.BodyHandlers.ofString(
						StandardCharsets.UTF_8));
	}

	private static void assertInputRequired(HttpResponse<String> response,
			String expectedId) {
		Assertions.assertEquals(200, response.statusCode(), response.body());
		Assertions.assertEquals(JSON_MEDIA_TYPE,
				response.headers().firstValue("Content-Type").orElseThrow());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
		assertContains(response.body(), "\"id\":\"" + expectedId + "\"");
		assertContains(response.body(), "\"resultType\":\"input_required\"");
	}

	private static void assertComplete(HttpResponse<String> response,
			String expectedId) {
		Assertions.assertEquals(200, response.statusCode(), response.body());
		assertContains(response.body(), "\"id\":\"" + expectedId + "\"");
		assertContains(response.body(), "\"resultType\":\"complete\"");
	}

	private static void assertMissingCapability(HttpResponse<String> response,
			String expectedId, String requiredCapabilities) {
		Assertions.assertEquals(400, response.statusCode(), response.body());
		Assertions.assertEquals(
				"{\"jsonrpc\":\"2.0\",\"id\":\"" + expectedId
						+ "\",\"error\":{\"code\":-32021,"
						+ "\"message\":\"Missing required client capability\","
						+ "\"data\":{\"requiredCapabilities\":"
						+ requiredCapabilities + "}}}",
				response.body());
	}

	private static void assertInternalErrorWithoutOutput(
			HttpResponse<String> response, String expectedId,
			String parameterSecret, String metadataSecret) {
		Assertions.assertEquals(500, response.statusCode(), response.body());
		Assertions.assertEquals(
				"{\"jsonrpc\":\"2.0\",\"id\":\"" + expectedId
						+ "\",\"error\":{\"code\":-32603,"
						+ "\"message\":\"Internal error\"}}",
				response.body());
		Assertions.assertFalse(response.body().contains(parameterSecret),
				response.body());
		Assertions.assertFalse(response.body().contains(metadataSecret),
				response.body());
		Assertions.assertFalse(response.body().contains("valid-first"),
				response.body());
		Assertions.assertFalse(response.body().contains("inputRequests"),
				response.body());
		Assertions.assertFalse(response.body().contains("\"result\""),
				response.body());
	}

	private static void assertCounts(int expected,
			AtomicInteger... counters) {
		for (AtomicInteger counter : counters)
			Assertions.assertEquals(expected, counter.get());
	}

	private static void assertContains(String text, String expected) {
		Assertions.assertTrue(text.contains(expected), () ->
				"Expected <" + text + "> to contain <" + expected + ">.");
	}
}
