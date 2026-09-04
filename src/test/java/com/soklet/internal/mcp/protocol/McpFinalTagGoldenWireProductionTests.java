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

package com.soklet.internal.mcp.protocol;

import com.soklet.CorsAuthorizer;
import com.soklet.McpBlobResourceContents;
import com.soklet.McpClientCapability;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpInputRequest;
import com.soklet.McpInputRequestDeclaration;
import com.soklet.McpInputRequiredResult;
import com.soklet.McpInputRequirement;
import com.soklet.McpJsonArray;
import com.soklet.McpJsonBoolean;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonString;
import com.soklet.McpSubscriptionEventPublisher;
import com.soklet.McpPromptArgumentDeclaration;
import com.soklet.McpPromptMessage;
import com.soklet.McpPromptOutput;
import com.soklet.McpPromptRegistration;
import com.soklet.McpProgressReporter;
import com.soklet.McpProgressUpdate;
import com.soklet.McpProtectionConfig;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpRequestStateMode;
import com.soklet.McpRequestStateProtectionContext;
import com.soklet.McpRequestStateProtectionException;
import com.soklet.McpRequestStateProtector;
import com.soklet.McpResourceOutput;
import com.soklet.McpResourceRegistration;
import com.soklet.McpServer;
import com.soklet.McpSubscriptionConfig;
import com.soklet.McpSubscriptionNotificationType;
import com.soklet.McpTextResourceContents;
import com.soklet.McpTextContent;
import com.soklet.McpToolRegistration;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class McpFinalTagGoldenWireProductionTests {
	private static final long MANAGED_STOP_JOIN_MILLIS = 20_000L;
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final Path GOLDEN_ROOT = Path.of(
			"conformance", "official", "golden-wire");

	@Test
	public void checked_in_phase_3_messages_match_the_production_listener() throws Exception {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"soklet-final-schema-golden", "4.0.0"))
				.serverInformationIncluded(true)
				.build();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(),
				ignored -> McpAdmissionDecision.acceptedAnonymous());

		try (McpHttpServerRuntime runtime = new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0), policy, endpoint)) {
			int port = runtime.start().getPort();

			assertExchange(port, fixture("phase-3/discover-request.json"), List.of(
					new McpChunkedHttpClient.RequestHeader(
							"MCP-Protocol-Version", PROTOCOL_VERSION),
					new McpChunkedHttpClient.RequestHeader("Mcp-Method", "server/discover")),
					200, fixture("phase-3/discover-response.json"));

			assertExchange(port, fixture("phase-3/discover-request.json"), List.of(
					new McpChunkedHttpClient.RequestHeader(
							"MCP-Protocol-Version", PROTOCOL_VERSION)),
					400, fixture("phase-3/header-mismatch-error.json"));

			assertExchange(port, fixture("phase-3/unsupported-version-request.json"), List.of(
					new McpChunkedHttpClient.RequestHeader(
							"MCP-Protocol-Version", "2025-11-25"),
					new McpChunkedHttpClient.RequestHeader("Mcp-Method", "server/discover")),
					400, fixture("phase-3/unsupported-version-error.json"));
		}
	}

	@Test
	public void checked_in_phase_3_unknown_method_messages_match_the_production_listener()
			throws Exception {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"soklet-final-schema-golden", "4.0.0"))
				.build();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(),
				ignored -> McpAdmissionDecision.acceptedAnonymous());

		try (McpHttpServerRuntime runtime = new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0), policy, endpoint)) {
			int port = runtime.start().getPort();
			assertErrorExchange(port, fixture("phase-3/unknown-method-request.json"),
					List.of(new McpChunkedHttpClient.RequestHeader(
							"MCP-Protocol-Version", PROTOCOL_VERSION),
						new McpChunkedHttpClient.RequestHeader(
								"Mcp-Method", "example/unknown")),
					404, McpJsonRpcError.METHOD_NOT_FOUND,
					"phase-3-unknown-method",
					fixture("phase-3/unknown-method-error.json"));
		}
	}

	@Test
	public void checked_in_phase_3_rate_limit_messages_match_the_production_listener()
			throws Exception {
		String rateLimitPartitionSecret = "golden-rate-partition-secret";
		AtomicInteger admissionInvocations = new AtomicInteger();
		AtomicInteger requestLimiterInvocations = new AtomicInteger();
		AtomicInteger toolLimiterInvocations = new AtomicInteger();
		AtomicInteger interceptorInvocations = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("golden.rate-limited")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText(
							"unexpected rate-limited handler execution");
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion(
						"soklet-final-schema-golden", "4.0.0").build())
				.addTool(tool)
				.build();
		McpServer server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))).admissionController(context -> {
					admissionInvocations.incrementAndGet();
					return com.soklet.McpAdmissionDecision.accepted(
							com.soklet.McpAdmissionIdentity
									.withRateLimitPartitionKey(rateLimitPartitionSecret)
									.build());
				})
				.host("127.0.0.1")
				.requestRateLimiter(context -> {
					int invocation = requestLimiterInvocations.incrementAndGet();
					Assertions.assertEquals(com.soklet.McpRateLimitTarget.REQUEST,
							context.getTarget());
					Assertions.assertEquals(rateLimitPartitionSecret,
							context.getAdmissionIdentity().getRateLimitPartitionKey());
					if (invocation == 1) {
						Assertions.assertEquals("tools/call", context.getJsonRpcMethod());
						Assertions.assertEquals("golden.rate-limited",
								context.getOperationName().orElseThrow());
					} else {
						Assertions.assertEquals(2, invocation);
						Assertions.assertEquals("notifications/cancelled",
								context.getJsonRpcMethod());
						Assertions.assertTrue(context.getOperationName().isEmpty());
					}
					return McpRateLimitDecision.denied(Duration.ofMillis(1_001L));
				})
				.toolRateLimiter(context -> {
					toolLimiterInvocations.incrementAndGet();
					return McpRateLimitDecision.allowed();
				})
				.handlerInterceptor((context, features, continuation) -> {
					interceptorInvocations.incrementAndGet();
					return continuation.proceed();
				})
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of("127.0.0.1"))
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			String response = assertRateLimitedErrorExchange(port,
					fixture("phase-3/rate-limited-tool-request.json"), List.of(
							new McpChunkedHttpClient.RequestHeader(
									"MCP-Protocol-Version", PROTOCOL_VERSION),
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Method", "tools/call"),
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Name", "golden.rate-limited")),
					"phase-3-rate-limited",
					fixture("phase-3/rate-limited-tool-error.json"), "2",
					rateLimitPartitionSecret);
			Assertions.assertFalse(response.contains(rateLimitPartitionSecret), response);
			Assertions.assertEquals(1, admissionInvocations.get());
			Assertions.assertEquals(1, requestLimiterInvocations.get());
			Assertions.assertEquals(0, toolLimiterInvocations.get());
			Assertions.assertEquals(0, interceptorInvocations.get());
			Assertions.assertEquals(0, handlerInvocations.get());

			assertRateLimitedNotificationExchange(port,
					fixture("phase-3/rate-limited-notification.json"),
					List.of(new McpChunkedHttpClient.RequestHeader(
							"MCP-Protocol-Version", PROTOCOL_VERSION)), "2",
					rateLimitPartitionSecret);
			Assertions.assertEquals(2, admissionInvocations.get());
			Assertions.assertEquals(2, requestLimiterInvocations.get());
			Assertions.assertEquals(0, toolLimiterInvocations.get());
			Assertions.assertEquals(0, interceptorInvocations.get());
			Assertions.assertEquals(0, handlerInvocations.get());
		} finally {
			owner.close();
		}
	}

	@Test
	public void checked_in_phase_4_prompt_messages_match_the_production_listener()
			throws Exception {
		McpPromptRegistration prompt = McpPromptRegistration
				.withName("golden.compose")
				.handler((request, promptGet, features) ->
						McpCompleteResult.fromPromptOutput(McpPromptOutput.builder()
								.description("Canonical rendered prompt")
								.addMessage(McpPromptMessage.fromUserContent(
										McpTextContent.fromText("subject="
												+ promptGet.findArgument("subject")
														.orElseThrow()
												+ ";tone="
												+ promptGet.findArgument("tone")
														.orElse("<absent>"))))
								.addMessage(McpPromptMessage.fromAssistantContent(
										McpTextContent.fromText("ready")))
								.build()).withMetadata(com.soklet.McpJsonObject.builder()
									.put("fixture", "phase-4-result").build()))
				.title("Golden composition")
				.description("Renders a canonical prompt")
				.addArgument(McpPromptArgumentDeclaration.withName("subject")
						.title("Subject")
						.description("Subject to render")
						.required(true)
						.build())
				.addArgument(McpPromptArgumentDeclaration.withName("tone")
						.description("Optional tone")
						.build())
				.metadata(com.soklet.McpJsonObject.builder()
						.put("fixture", "phase-4").build())
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion(
						"soklet-final-schema-golden", "4.0.0").build())
				.addPrompt(prompt)
				.build();
		McpServer server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.host("127.0.0.1")
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of("127.0.0.1"))
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();

			assertExchange(port, fixture("phase-4/prompts-list-request.json"), List.of(
					new McpChunkedHttpClient.RequestHeader(
							"MCP-Protocol-Version", PROTOCOL_VERSION),
					new McpChunkedHttpClient.RequestHeader("Mcp-Method", "prompts/list")),
					200, fixture("phase-4/prompts-list-response.json"));

			assertExchange(port, fixture("phase-4/prompts-get-request.json"), List.of(
					new McpChunkedHttpClient.RequestHeader(
							"MCP-Protocol-Version", PROTOCOL_VERSION),
					new McpChunkedHttpClient.RequestHeader("Mcp-Method", "prompts/get"),
					new McpChunkedHttpClient.RequestHeader("Mcp-Name", "golden.compose")),
					200, fixture("phase-4/prompts-get-response.json"));
		} finally {
			owner.close();
		}
	}

	@Test
	public void checked_in_phase_4_resource_messages_match_the_production_listener()
			throws Exception {
		URI textResourceUri = URI.create("golden://documents/readme");
		McpResourceRegistration textResource = McpResourceRegistration
				.withUriAndName(textResourceUri, "Golden README")
				.handler((request, resource, features) ->
						McpCompleteResult.fromResourceOutput(McpResourceOutput.withContent(McpTextResourceContents.withUriAndText(
										resource.getUri(), "Soklet golden resource")
										.mimeType("text/plain")
										.build())
								.build()))
				.mimeType("text/plain")
				.sizeInBytes(22L)
				.build();

		URI blobResourceUri = URI.create("golden://assets/logo.bin");
		McpResourceRegistration blobResource = McpResourceRegistration
				.withUriAndName(blobResourceUri, "Golden bytes")
				.handler((request, resource, features) ->
						McpCompleteResult.fromResourceOutput(McpResourceOutput.withContent(McpBlobResourceContents.withUriAndData(
										resource.getUri(),
										new byte[]{0x00, 0x01, 0x02, (byte) 0xFF})
										.mimeType("application/octet-stream")
										.build())
								.build()))
				.mimeType("application/octet-stream")
				.sizeInBytes(4L)
				.build();

		McpResourceRegistration recordTemplate = McpResourceRegistration
				.withUriTemplateAndName(
						"golden://records/{recordId}", "Golden record")
				.handler((request, resource, features) ->
						McpCompleteResult.fromResourceOutput(McpResourceOutput.withContent(McpTextResourceContents.withUriAndText(
										resource.getUri(), "recordId="
												+ resource.getUriTemplateVariables()
														.get("recordId"))
										.mimeType("text/plain")
										.build())
								.build()))
				.mimeType("text/plain")
				.build();

		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion(
						"soklet-final-schema-golden", "4.0.0").build())
				.addResource(textResource)
				.addResource(blobResource)
				.addResource(recordTemplate)
				.build();
		McpServer server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.host("127.0.0.1")
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of("127.0.0.1"))
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();

			assertExchange(port, fixture("phase-4/resources-list-request.json"), List.of(
					new McpChunkedHttpClient.RequestHeader(
							"MCP-Protocol-Version", PROTOCOL_VERSION),
					new McpChunkedHttpClient.RequestHeader(
							"Mcp-Method", "resources/list")),
					200, fixture("phase-4/resources-list-response.json"));

			assertExchange(port,
					fixture("phase-4/resources-templates-list-request.json"), List.of(
							new McpChunkedHttpClient.RequestHeader(
									"MCP-Protocol-Version", PROTOCOL_VERSION),
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Method", "resources/templates/list")),
					200, fixture("phase-4/resources-templates-list-response.json"));

			assertResourceRead(port, "blob", blobResourceUri.toString(), 200);
			assertResourceRead(port, "template", "golden://records/record-42", 200);
			assertResourceRead(port, "text", textResourceUri.toString(), 200);
			assertResourceRead(port, "unknown", "golden://missing/resource", 400);
		} finally {
			owner.close();
		}
	}

	@Test
	public void checked_in_phase_4_strict_unknown_header_messages_match_the_production_listener()
			throws Exception {
		String unknownHeaderName = "Mcp-Param-Super-Secret-Name";
		String unknownHeaderValue = "super-secret-value";
		AtomicInteger admissionInvocations = new AtomicInteger();
		AtomicInteger requestLimiterInvocations = new AtomicInteger();
		AtomicInteger toolLimiterInvocations = new AtomicInteger();
		AtomicInteger interceptorInvocations = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("golden.strict-unknown")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText(
							"unexpected strict-unknown handler execution");
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion(
						"soklet-final-schema-golden", "4.0.0").build())
				.addTool(tool)
				.build();
		McpServer server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))).admissionController(context -> {
					admissionInvocations.incrementAndGet();
					return com.soklet.McpAdmissionDecision.accepted();
				})
				.host("127.0.0.1")
				.requestRateLimiter(context -> {
					requestLimiterInvocations.incrementAndGet();
					return McpRateLimitDecision.allowed();
				})
				.toolRateLimiter(context -> {
					toolLimiterInvocations.incrementAndGet();
					return McpRateLimitDecision.allowed();
				})
				.handlerInterceptor((context, features, continuation) -> {
					interceptorInvocations.incrementAndGet();
					return continuation.proceed();
				})
				.unknownMirroredHeaderPolicy(
						com.soklet.McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of("127.0.0.1"))
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			String response = assertErrorExchange(port,
					fixture("phase-4/strict-unknown-header-request.json"), List.of(
							new McpChunkedHttpClient.RequestHeader(
									"MCP-Protocol-Version", PROTOCOL_VERSION),
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Method", "tools/call"),
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Name", "golden.strict-unknown"),
							new McpChunkedHttpClient.RequestHeader(
									unknownHeaderName, unknownHeaderValue)),
					400, com.soklet.McpJsonRpcError
							.SOKLET_STRICT_UNKNOWN_MIRRORED_HEADER_ERROR_CODE,
					"phase-4-strict-unknown",
					fixture("phase-4/strict-unknown-header-error.json"),
					unknownHeaderName, "Super-Secret-Name", unknownHeaderValue);
			Assertions.assertFalse(response.contains(unknownHeaderName), response);
			Assertions.assertFalse(response.contains("Super-Secret-Name"), response);
			Assertions.assertFalse(response.contains(unknownHeaderValue), response);
			Assertions.assertEquals(0, admissionInvocations.get());
			Assertions.assertEquals(0, requestLimiterInvocations.get());
			Assertions.assertEquals(0, toolLimiterInvocations.get());
			Assertions.assertEquals(0, interceptorInvocations.get());
			Assertions.assertEquals(0, handlerInvocations.get());
		} finally {
			owner.close();
		}
	}

	@Test
	public void checked_in_phase_5_input_messages_match_the_production_listener()
			throws Exception {
		McpInputRequestDeclaration form = McpInputRequestDeclaration
				.fromElicitationForm(McpInputRequirement.CONDITIONAL);
		McpInputRequestDeclaration url = McpInputRequestDeclaration
				.fromElicitationUrl(McpInputRequirement.CONDITIONAL);
		McpInputRequestDeclaration sampling = McpInputRequestDeclaration
				.fromSampling(Set.of(McpClientCapability.SAMPLING_CONTEXT,
						McpClientCapability.SAMPLING_TOOLS),
						McpInputRequirement.CONDITIONAL);
		McpInputRequestDeclaration roots = McpInputRequestDeclaration
				.fromRoots(McpInputRequirement.CONDITIONAL);
		McpJsonObject requestedSchema = McpJsonObject.builder()
				.put("type", "object")
				.put("properties", McpJsonObject.builder()
						.put("answer", McpJsonObject.builder()
								.put("type", "string")
								.put("description", "Canonical answer")
								.build())
						.build())
				.put("required", McpJsonArray.builder().add("answer").build())
				.build();
		McpJsonObject formParams = McpJsonObject.builder()
				.put("message", "Provide the canonical answer")
				.put("requestedSchema", requestedSchema)
				.build();
		McpJsonObject urlParams = McpJsonObject.builder()
				.put("message", "Continue in the canonical browser flow")
				.put("mode", "url")
				.put("url", "https://example.test/continue")
				.build();
		McpJsonObject samplingParams = McpJsonObject.builder()
				.put("maxTokens", 64)
				.put("messages", McpJsonArray.builder()
						.add(McpJsonObject.builder()
								.put("role", "user")
								.put("content", McpJsonObject.builder()
										.put("type", "text")
										.put("text", "Return one canonical word")
										.build())
								.build())
						.build())
				.put("includeContext", "thisServer")
				.put("tools", McpJsonArray.builder()
						.add(McpJsonObject.builder()
								.put("name", "golden.lookup")
								.put("inputSchema", McpJsonObject.builder()
										.put("type", "object")
										.build())
								.build())
						.build())
				.put("toolChoice", McpJsonObject.builder()
						.put("mode", "auto")
						.build())
				.build();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("golden.input-required")
				.jsonObjectArguments()
				.handler((request, arguments, features) ->
						McpInputRequiredResult.withInputRequest("form", McpInputRequest.fromDeclaration(
										form, formParams))
								.addInputRequest("url", McpInputRequest.fromDeclaration(
										url, urlParams))
								.addInputRequest("sampling", McpInputRequest.fromDeclaration(
										sampling, samplingParams))
								.addInputRequest("roots", McpInputRequest.fromDeclaration(
										roots,
												McpJsonObject.emptyInstance()))
								.metadata(McpJsonObject.builder()
										.put("fixture", "phase-5-input-required")
										.build())
								.build())
				.addInputRequestDeclarations(form, url, sampling, roots)
				.build();
		McpToolRegistration<McpJsonObject> inputResponsesTool = McpToolRegistration
				.withName("golden.input-responses")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					McpJsonObject response = Assertions.assertInstanceOf(
							McpJsonObject.class, request.getInputResponses()
									.find("approval").orElseThrow());
					Assertions.assertEquals("accept", Assertions.assertInstanceOf(
							McpJsonString.class,
							response.find("action").orElseThrow()).getValue());
					McpJsonObject extension = Assertions.assertInstanceOf(
							McpJsonObject.class, response
									.find("com.example/responseExtension")
									.orElseThrow());
					Assertions.assertTrue(Assertions.assertInstanceOf(
							McpJsonBoolean.class,
							extension.find("preserved").orElseThrow()).getValue());
					return McpCompleteResult.fromToolText(
							"input responses accepted");
				})
				.addInputRequestDeclarations(form)
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion(
						"soklet-final-schema-golden", "4.0.0").build())
				.addTool(tool)
				.addTool(inputResponsesTool)
				.build();
		McpServer server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.host("127.0.0.1")
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of("127.0.0.1"))
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			assertExchange(port,
					fixture("phase-5/input-required-tool-request.json"), List.of(
							new McpChunkedHttpClient.RequestHeader(
									"MCP-Protocol-Version", PROTOCOL_VERSION),
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Method", "tools/call"),
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Name", "golden.input-required")),
					200, fixture("phase-5/input-required-tool-response.json"));
			assertExchange(port,
					fixture("phase-5/input-responses-tool-request.json"), List.of(
							new McpChunkedHttpClient.RequestHeader(
									"MCP-Protocol-Version", PROTOCOL_VERSION),
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Method", "tools/call"),
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Name", "golden.input-responses")),
					200, fixture("phase-5/input-responses-tool-response.json"));
		} finally {
			owner.close();
		}
	}

	@Test
	public void checked_in_phase_5_missing_capability_messages_match_the_production_listener()
			throws Exception {
		AtomicInteger admissionInvocations = new AtomicInteger();
		AtomicInteger requestLimiterInvocations = new AtomicInteger();
		AtomicInteger toolLimiterInvocations = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpInputRequestDeclaration form = McpInputRequestDeclaration
				.fromElicitationForm(McpInputRequirement.REQUIRED);
		McpInputRequestDeclaration url = McpInputRequestDeclaration
				.fromElicitationUrl(McpInputRequirement.REQUIRED);
		McpInputRequestDeclaration sampling = McpInputRequestDeclaration
				.fromSampling(new LinkedHashSet<>(List.of(
						McpClientCapability.SAMPLING_CONTEXT,
						McpClientCapability.SAMPLING_TOOLS)),
						McpInputRequirement.REQUIRED);
		McpInputRequestDeclaration roots = McpInputRequestDeclaration
				.fromRoots(McpInputRequirement.REQUIRED);
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("golden.missing-capability")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText("unexpected handler execution");
				})
				.addInputRequestDeclarations(form, url, sampling, roots)
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion(
						"soklet-final-schema-golden", "4.0.0").build())
				.addTool(tool)
				.build();
		McpServer server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))).admissionController(context -> {
					admissionInvocations.incrementAndGet();
					return com.soklet.McpAdmissionDecision.accepted();
				})
				.host("127.0.0.1")
				.requestRateLimiter(context -> {
					requestLimiterInvocations.incrementAndGet();
					return McpRateLimitDecision.allowed();
				})
				.toolRateLimiter(context -> {
					toolLimiterInvocations.incrementAndGet();
					return McpRateLimitDecision.allowed();
				})
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of("127.0.0.1"))
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			assertErrorExchange(port,
					fixture("phase-5/missing-capability-tool-request.json"), List.of(
							new McpChunkedHttpClient.RequestHeader(
									"MCP-Protocol-Version", PROTOCOL_VERSION),
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Method", "tools/call"),
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Name", "golden.missing-capability")),
					400, McpJsonRpcError.MISSING_REQUIRED_CLIENT_CAPABILITY,
					"phase-5-missing-capability",
					fixture("phase-5/missing-capability-error.json"));
			Assertions.assertEquals(0, admissionInvocations.get());
			Assertions.assertEquals(0, requestLimiterInvocations.get());
			Assertions.assertEquals(0, toolLimiterInvocations.get());
			Assertions.assertEquals(0, handlerInvocations.get());
		} finally {
			owner.close();
		}
	}

	@Test
	public void checked_in_phase_5_progress_messages_match_the_production_listener()
			throws Exception {
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("golden.progress")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					McpProgressReporter reporter =
							features.require(McpProgressReporter.class);
					reporter.report(McpProgressUpdate.withProgress(0.0d)
							.total(100.0d).build());
					reporter.report(McpProgressUpdate.withProgress(50.0d)
							.total(100.0d).build());
					reporter.report(McpProgressUpdate.withProgress(100.0d)
							.total(100.0d).build());
					return McpCompleteResult.fromToolText("progress complete");
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion(
						"soklet-final-schema-golden", "4.0.0").build())
				.addTool(tool)
				.build();
		McpServer server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.host("127.0.0.1")
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of("127.0.0.1"))
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcpMessage(
					port, fixture("phase-5/progress-tool-request.json"), List.of(
							new McpChunkedHttpClient.RequestHeader(
									"MCP-Protocol-Version", PROTOCOL_VERSION),
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Method", "tools/call"),
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Name", "golden.progress")))) {
				McpChunkedHttpClient.HttpResponseHead head = client.readHead();
				Assertions.assertEquals(200, head.status(), head.raw());
				Assertions.assertEquals("text/event-stream",
						head.singleHeader("Content-Type"));
				Assertions.assertEquals("no-store",
						head.singleHeader("Cache-Control"));
				Assertions.assertEquals("chunked",
						head.singleHeader("Transfer-Encoding"));
				Assertions.assertEquals(sseFixture(
						"phase-5/progress-notification-0.json"),
						client.readChunkText());
				Assertions.assertEquals(sseFixture(
						"phase-5/progress-notification-50.json"),
						client.readChunkText());
				Assertions.assertEquals(sseFixture(
						"phase-5/progress-notification-100.json"),
						client.readChunkText());
				Assertions.assertEquals(sseFixture(
						"phase-5/progress-tool-response.json"),
						client.readChunkText());
				Assertions.assertNull(client.readChunk());
			}
		} finally {
			owner.close();
		}
	}

	@Test
	public void checked_in_phase_5_subscription_messages_match_the_production_listener()
			throws Exception {
		URI resourceUri = URI.create("golden://subscriptions/resource");
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisher(publisher, Set.of(
						McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED,
						McpSubscriptionNotificationType.RESOURCE_UPDATED))
				.build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(resourceUri, "Golden subscription resource")
				.handler((request, read, features) ->
						McpCompleteResult.fromResourceOutput(McpResourceOutput.withContent(McpTextResourceContents.withUriAndText(
										read.getUri(), "subscription golden")
										.build())
								.build()))
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion(
						"soklet-final-schema-golden", "4.0.0").build())
				.addResource(resource)
				.subscriptionConfig(subscriptions)
				.build();
		McpServer server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.host("127.0.0.1")
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of("127.0.0.1"))
				.build();
		Soklet owner = managedSoklet(server);
		McpChunkedHttpClient client = null;
		Thread stopThread = null;

		try {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			client = McpChunkedHttpClient.postMcpMessage(port,
					fixture("phase-5/subscription-listen-request.json"), List.of(
							new McpChunkedHttpClient.RequestHeader(
									"MCP-Protocol-Version", PROTOCOL_VERSION),
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Method", "subscriptions/listen")));
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			Assertions.assertEquals(200, head.status(), head.raw());
			Assertions.assertEquals("text/event-stream",
					head.singleHeader("Content-Type"));
			Assertions.assertEquals("no-store",
					head.singleHeader("Cache-Control"));
			Assertions.assertEquals("no",
					head.singleHeader("X-Accel-Buffering"));
			Assertions.assertEquals("chunked",
					head.singleHeader("Transfer-Encoding"));
			Assertions.assertFalse(head.hasHeader("Content-Length"));
			Assertions.assertEquals(sseFixture(
					"phase-5/subscription-acknowledged.json"),
					client.readChunkText());

			publisher.publishResourcesListChanged();
			Assertions.assertEquals(sseFixture(
					"phase-5/subscription-resource-list-changed.json"),
					client.readChunkText());
			publisher.publishResourceUpdated(resourceUri);
			Assertions.assertEquals(sseFixture(
					"phase-5/subscription-resource-updated.json"),
					client.readChunkText());

			stopThread = new Thread(owner::close,
					"mcp-subscription-golden-stop");
			stopThread.start();
			Assertions.assertEquals(sseFixture(
					"phase-5/subscription-listen-response.json"),
					client.readChunkText());
			Assertions.assertNull(client.readChunk());
			stopThread.join(MANAGED_STOP_JOIN_MILLIS);
			Assertions.assertFalse(stopThread.isAlive());
		} finally {
			if (client != null)
				client.close();
			owner.close();
			if (stopThread != null && stopThread.isAlive())
				stopThread.join(MANAGED_STOP_JOIN_MILLIS);
		}
	}

	@Test
	public void checked_in_phase_5_protected_state_round_trip_matches_the_production_listener()
			throws Exception {
		McpInputRequestDeclaration form = McpInputRequestDeclaration
				.fromElicitationForm(McpInputRequirement.CONDITIONAL);
		McpJsonObject requestedSchema = McpJsonObject.builder()
				.put("type", "object")
				.put("properties", McpJsonObject.builder()
						.put("answer", McpJsonObject.builder()
								.put("type", "string")
								.put("description", "Protected-state answer")
								.build())
						.build())
				.put("required", McpJsonArray.builder().add("answer").build())
				.build();
		McpJsonObject frameworkState = McpJsonObject.builder()
				.put("phase", "awaiting-approval")
				.put("fixture", "phase-5-protected-state")
				.build();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("golden.protected-state")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					if (request.getFrameworkRequestState().isEmpty()) {
						Assertions.assertTrue(
								request.getInputResponses().asMap().isEmpty());
						return McpInputRequiredResult.withInputRequest("approval", McpInputRequest.fromDeclaration(
										form,
												McpJsonObject.builder()
														.put("message",
																"Approve the protected-state golden exchange")
														.put("requestedSchema",
																requestedSchema)
														.build()))
								.frameworkRequestState(frameworkState)
								.metadata(McpJsonObject.builder()
										.put("fixture", "phase-5-protected-state")
										.build())
								.build();
					}

					McpJsonObject stateValue = Assertions.assertInstanceOf(
							McpJsonObject.class,
							request.getFrameworkRequestState().orElseThrow());
					Assertions.assertEquals("awaiting-approval",
							Assertions.assertInstanceOf(McpJsonString.class,
									stateValue.find("phase").orElseThrow()).getValue());
					Assertions.assertEquals("phase-5-protected-state",
							Assertions.assertInstanceOf(McpJsonString.class,
									stateValue.find("fixture").orElseThrow()).getValue());
					McpJsonObject approval = Assertions.assertInstanceOf(
							McpJsonObject.class, request.getInputResponses()
									.find("approval").orElseThrow());
					Assertions.assertEquals("accept", Assertions.assertInstanceOf(
							McpJsonString.class,
							approval.find("action").orElseThrow()).getValue());
					McpJsonObject content = Assertions.assertInstanceOf(
							McpJsonObject.class,
							approval.find("content").orElseThrow());
					Assertions.assertEquals("approved",
							Assertions.assertInstanceOf(McpJsonString.class,
									content.find("answer").orElseThrow()).getValue());
					return McpCompleteResult.fromToolText(
							"protected request state accepted");
				})
				.addInputRequestDeclarations(form)
				.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion(
						"soklet-final-schema-golden", "4.0.0").build())
				.addTool(tool)
				.build();
		McpServer server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.host("127.0.0.1")
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.protectionConfig(McpProtectionConfig.withRequestStateProtector(
						new DeterministicGoldenRequestStateProtector()).build())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of("127.0.0.1"))
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			assertProtectedStateExchange(port,
					"phase-5/protected-state-initial-request.json",
					"phase-5/protected-state-initial-response.json");
			assertProtectedStateExchange(port,
					"phase-5/protected-state-retry-request.json",
					"phase-5/protected-state-retry-response.json");
		} finally {
			owner.close();
		}
	}

	private static Soklet managedSoklet(McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build());
	}

	private static void assertProtectedStateExchange(int port, String requestFixture,
			String responseFixture) throws Exception {
		assertExchange(port, fixture(requestFixture), List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", PROTOCOL_VERSION),
				new McpChunkedHttpClient.RequestHeader("Mcp-Method", "tools/call"),
				new McpChunkedHttpClient.RequestHeader(
						"Mcp-Name", "golden.protected-state")),
				200, fixture(responseFixture));
	}

	private static void assertResourceRead(int port, String fixtureName, String uri,
			int expectedStatus) throws Exception {
		assertExchange(port,
				fixture("phase-4/resources-read-" + fixtureName + "-request.json"),
				List.of(new McpChunkedHttpClient.RequestHeader(
							"MCP-Protocol-Version", PROTOCOL_VERSION),
						new McpChunkedHttpClient.RequestHeader(
								"Mcp-Method", "resources/read"),
						new McpChunkedHttpClient.RequestHeader("Mcp-Name", uri)),
				expectedStatus,
				fixture("phase-4/resources-read-" + fixtureName + "-response.json"));
	}

	private static void assertExchange(int port, String request,
			List<McpChunkedHttpClient.RequestHeader> headers, int expectedStatus,
			String expectedResponse) throws Exception {
		try (McpChunkedHttpClient client =
					McpChunkedHttpClient.postMcpMessage(port, request, headers)) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			Assertions.assertEquals(expectedStatus, head.status(), head.raw());
			Assertions.assertEquals("application/json", head.singleHeader("Content-Type"));
			Assertions.assertEquals("no-store", head.singleHeader("Cache-Control"));
			Assertions.assertEquals(expectedResponse, client.readFixedBody(head));
		}
	}

	private static String assertErrorExchange(int port, String request,
			List<McpChunkedHttpClient.RequestHeader> headers, int expectedStatus,
			int expectedCode, String expectedRequestId, String expectedResponse,
			String... prohibitedText)
			throws Exception {
		try (McpChunkedHttpClient client =
					McpChunkedHttpClient.postMcpMessage(port, request, headers)) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			Assertions.assertEquals(expectedStatus, head.status(), head.raw());
			Assertions.assertEquals("application/json",
					head.singleHeader("Content-Type"));
			Assertions.assertEquals("no-store", head.singleHeader("Cache-Control"));
			Assertions.assertFalse(head.hasHeader("Retry-After"));
			assertProhibitedTextAbsent(head.raw(), prohibitedText);
			String response = client.readFixedBody(head);
			Assertions.assertEquals(expectedResponse, response);
			assertProhibitedTextAbsent(response, prohibitedText);
			assertErrorBody(response, expectedCode, expectedRequestId);
			return response;
		}
	}

	private static String assertRateLimitedErrorExchange(int port, String request,
			List<McpChunkedHttpClient.RequestHeader> headers, String expectedRequestId,
			String expectedResponse, String expectedRetryAfter,
			String... prohibitedText) throws Exception {
		try (McpChunkedHttpClient client =
					McpChunkedHttpClient.postMcpMessage(port, request, headers)) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			Assertions.assertEquals(429, head.status(), head.raw());
			Assertions.assertEquals("application/json",
					head.singleHeader("Content-Type"));
			Assertions.assertEquals("no-store", head.singleHeader("Cache-Control"));
			Assertions.assertEquals(expectedRetryAfter,
					head.singleHeader("Retry-After"));
			assertProhibitedTextAbsent(head.raw(), prohibitedText);
			String response = client.readFixedBody(head);
			Assertions.assertEquals(expectedResponse, response);
			assertProhibitedTextAbsent(response, prohibitedText);
			assertErrorBody(response,
					com.soklet.McpJsonRpcError.SOKLET_RATE_LIMIT_ERROR_CODE,
					expectedRequestId);
			return response;
		}
	}

	private static void assertRateLimitedNotificationExchange(int port, String request,
			List<McpChunkedHttpClient.RequestHeader> headers, String expectedRetryAfter,
			String... prohibitedText)
			throws Exception {
		try (McpChunkedHttpClient client =
					McpChunkedHttpClient.postMcpMessage(port, request, headers)) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			Assertions.assertEquals(429, head.status(), head.raw());
			Assertions.assertEquals("no-store", head.singleHeader("Cache-Control"));
			Assertions.assertEquals(expectedRetryAfter,
					head.singleHeader("Retry-After"));
			Assertions.assertFalse(head.hasHeader("Content-Type"));
			assertProhibitedTextAbsent(head.raw(), prohibitedText);
			String response = client.readFixedBody(head);
			Assertions.assertEquals("", response);
			assertProhibitedTextAbsent(response, prohibitedText);
		}
	}

	private static void assertProhibitedTextAbsent(String text,
			String... prohibitedText) {
		for (String prohibited : prohibitedText)
			Assertions.assertFalse(text.contains(prohibited), text);
	}

	private static void assertErrorBody(String response, int expectedCode,
			String expectedRequestId) {
		McpJsonRpcEnvelope.ErrorResponse errorResponse = Assertions.assertInstanceOf(
					McpJsonRpcEnvelope.ErrorResponse.class,
					new McpJsonRpcEnvelopeCodec(new McpJsonCodec(
							McpJsonLimits.productionDefaults())).decode(response));
		Assertions.assertEquals(new McpJsonRpcId.StringId(expectedRequestId),
				errorResponse.id().orElseThrow());
		com.soklet.internal.mcp.protocol.McpJsonObject error =
				Assertions.assertInstanceOf(
						com.soklet.internal.mcp.protocol.McpJsonObject.class,
						errorResponse.error());
		Assertions.assertEquals(new McpJsonNumber(expectedCode),
				error.members().get("code"));
	}

	private static String fixture(String filename) throws Exception {
		String text = Files.readString(GOLDEN_ROOT.resolve(filename), StandardCharsets.UTF_8);
		Assertions.assertFalse(text.contains("\r"), filename + " must use LF");
		Assertions.assertTrue(text.endsWith("\n"), filename + " must end with LF");
		Assertions.assertFalse(text.substring(0, text.length() - 1).contains("\n"),
				filename + " must contain one compact JSON line");
		return text.substring(0, text.length() - 1);
	}

	private static String sseFixture(String filename) throws Exception {
		return "data: " + fixture(filename) + "\n\n";
	}

	private static final class DeterministicGoldenRequestStateProtector
			implements McpRequestStateProtector {
		private static final String PROTECTED_STATE =
				"phase-5-protected-state-v1";
		private final AtomicReference<ProtectedSnapshot> protectedSnapshot =
				new AtomicReference<>();

		@Override
		public String seal(McpRequestStateProtectionContext context,
				byte[] plaintext) {
			ProtectedSnapshot snapshot = new ProtectedSnapshot(
					context.getAssociatedData(), plaintext);
			if (!this.protectedSnapshot.compareAndSet(null, snapshot))
				throw new IllegalStateException(
						"The golden protector supports one protected state.");
			return PROTECTED_STATE;
		}

		@Override
		public byte[] open(McpRequestStateProtectionContext context,
				String protectedState)
				throws McpRequestStateProtectionException {
			ProtectedSnapshot snapshot = this.protectedSnapshot.get();
			if (!PROTECTED_STATE.equals(protectedState) || snapshot == null
					|| !snapshot.matches(context.getAssociatedData()))
				throw McpRequestStateProtectionException.fromInvalidState();
			return snapshot.copyPlaintext();
		}
	}

	private static final class ProtectedSnapshot {
		private final byte[] associatedData;
		private final byte[] plaintext;

		private ProtectedSnapshot(byte[] associatedData, byte[] plaintext) {
			this.associatedData = associatedData.clone();
			this.plaintext = plaintext.clone();
		}

		private boolean matches(byte[] associatedData) {
			return MessageDigest.isEqual(this.associatedData, associatedData);
		}

		private byte[] copyPlaintext() {
			return this.plaintext.clone();
		}
	}
}
