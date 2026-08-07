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
import com.soklet.McpFrameworkRequestState;
import com.soklet.McpHandlerResolver;
import com.soklet.McpImplementation;
import com.soklet.McpInputRequest;
import com.soklet.McpInputRequestDeclaration;
import com.soklet.McpInputRequiredResult;
import com.soklet.McpInputRequirement;
import com.soklet.McpJsonArray;
import com.soklet.McpJsonBoolean;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonString;
import com.soklet.McpPromptArgumentDefinition;
import com.soklet.McpPromptMessage;
import com.soklet.McpPromptOutput;
import com.soklet.McpPromptRegistration;
import com.soklet.McpProtectionConfig;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpRequestAdmissionPolicy;
import com.soklet.McpRequestStateMode;
import com.soklet.McpRequestStateProtectionContext;
import com.soklet.McpRequestStateProtectionException;
import com.soklet.McpRequestStateProtector;
import com.soklet.McpResourceOutput;
import com.soklet.McpResourceRegistration;
import com.soklet.McpServer;
import com.soklet.McpTextResourceContents;
import com.soklet.McpTextContent;
import com.soklet.McpToolRegistration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class McpFinalTagGoldenWireProductionTests {
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final Path GOLDEN_ROOT = Path.of(
			"conformance", "official", "golden-wire");

	@Test
	public void checked_in_phase_3_messages_match_the_production_listener() throws Exception {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"soklet-final-schema-golden", "3.6.0-SNAPSHOT"))
				.includeServerInformation(true)
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
	public void checked_in_phase_4_prompt_messages_match_the_production_listener()
			throws Exception {
		McpPromptRegistration prompt = McpPromptRegistration
				.withName("golden.compose")
				.handler((request, promptGet, features) ->
						McpCompleteResult.fromPromptOutput(McpPromptOutput.builder()
								.description("Canonical rendered prompt")
								.message(McpPromptMessage.fromUserContent(
										McpTextContent.fromText("subject="
												+ promptGet.findArgument("subject")
														.orElseThrow()
												+ ";tone="
												+ promptGet.findArgument("tone")
														.orElse("<absent>"))))
								.message(McpPromptMessage.fromAssistantContent(
										McpTextContent.fromText("ready")))
								.build()).withMetadata(com.soklet.McpJsonObject.builder()
									.put("fixture", "phase-4-result").build()))
				.title("Golden composition")
				.description("Renders a canonical prompt")
				.argument(McpPromptArgumentDefinition.withName("subject")
						.title("Subject")
						.description("Subject to render")
						.required(true)
						.build())
				.argument(McpPromptArgumentDefinition.withName("tone")
						.description("Optional tone")
						.build())
				.metadata(com.soklet.McpJsonObject.builder()
						.put("fixture", "phase-4").build())
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp")
				.serverInformation(McpImplementation.withNameAndVersion(
						"soklet-final-schema-golden", "3.6.0-SNAPSHOT").build())
				.prompt(prompt)
				.build();
		McpServer server = McpServer.withPort(0)
				.host("127.0.0.1")
				.handlerResolver(McpHandlerResolver.fromEndpoints(List.of(endpoint)))
				.requestAdmissionPolicy(McpRequestAdmissionPolicy.acceptAllInstance())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of("127.0.0.1"))
				.build();

		try {
			server.start();
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
			server.stop();
		}
	}

	@Test
	public void checked_in_phase_4_resource_messages_match_the_production_listener()
			throws Exception {
		URI textResourceUri = URI.create("golden://documents/readme");
		McpResourceRegistration textResource = McpResourceRegistration
				.withUriAndName(textResourceUri, "Golden README")
				.handler((request, resource, features) ->
						McpCompleteResult.fromResourceOutput(McpResourceOutput.builder()
								.content(McpTextResourceContents.withUriAndText(
										resource.getUri(), "Soklet golden resource")
										.mimeType("text/plain")
										.build())
								.build()))
				.mimeType("text/plain")
				.size(22)
				.build();

		URI blobResourceUri = URI.create("golden://assets/logo.bin");
		McpResourceRegistration blobResource = McpResourceRegistration
				.withUriAndName(blobResourceUri, "Golden bytes")
				.handler((request, resource, features) ->
						McpCompleteResult.fromResourceOutput(McpResourceOutput.builder()
								.content(McpBlobResourceContents.withUriAndData(
										resource.getUri(),
										new byte[]{0x00, 0x01, 0x02, (byte) 0xFF})
										.mimeType("application/octet-stream")
										.build())
								.build()))
				.mimeType("application/octet-stream")
				.size(4)
				.build();

		McpResourceRegistration recordTemplate = McpResourceRegistration
				.withUriTemplateAndName(
						"golden://records/{recordId}", "Golden record")
				.handler((request, resource, features) ->
						McpCompleteResult.fromResourceOutput(McpResourceOutput.builder()
								.content(McpTextResourceContents.withUriAndText(
										resource.getUri(), "recordId="
												+ resource.getUriTemplateVariables()
														.get("recordId"))
										.mimeType("text/plain")
										.build())
								.build()))
				.mimeType("text/plain")
				.build();

		McpEndpoint endpoint = McpEndpoint.withPath("/mcp")
				.serverInformation(McpImplementation.withNameAndVersion(
						"soklet-final-schema-golden", "3.6.0-SNAPSHOT").build())
				.resource(textResource)
				.resource(blobResource)
				.resource(recordTemplate)
				.build();
		McpServer server = McpServer.withPort(0)
				.host("127.0.0.1")
				.handlerResolver(McpHandlerResolver.fromEndpoints(List.of(endpoint)))
				.requestAdmissionPolicy(McpRequestAdmissionPolicy.acceptAllInstance())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of("127.0.0.1"))
				.build();

		try {
			server.start();
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
			server.stop();
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
				.jsonArguments()
				.handler((request, call, features) ->
						McpInputRequiredResult.builder()
								.inputRequest("form", McpInputRequest
										.fromDeclaration(form, formParams))
								.inputRequest("url", McpInputRequest
										.fromDeclaration(url, urlParams))
								.inputRequest("sampling", McpInputRequest
										.fromDeclaration(sampling, samplingParams))
								.inputRequest("roots", McpInputRequest
										.fromDeclaration(roots,
												McpJsonObject.emptyInstance()))
								.metadata(McpJsonObject.builder()
										.put("fixture", "phase-5-input-required")
										.build())
								.build())
				.mayRequestInput(form, url, sampling, roots)
				.build();
		McpToolRegistration<McpJsonObject> inputResponsesTool = McpToolRegistration
				.withName("golden.input-responses")
				.jsonArguments()
				.handler((request, call, features) -> {
					McpJsonObject response = Assertions.assertInstanceOf(
							McpJsonObject.class, request.getInputResponses()
									.find("approval").orElseThrow());
					Assertions.assertEquals("accept", Assertions.assertInstanceOf(
							McpJsonString.class,
							response.find("action").orElseThrow()).value());
					McpJsonObject extension = Assertions.assertInstanceOf(
							McpJsonObject.class, response
									.find("com.example/responseExtension")
									.orElseThrow());
					Assertions.assertTrue(Assertions.assertInstanceOf(
							McpJsonBoolean.class,
							extension.find("preserved").orElseThrow()).value());
					return McpCompleteResult.fromToolText(
							"input responses accepted");
				})
				.mayRequestInput(form)
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp")
				.serverInformation(McpImplementation.withNameAndVersion(
						"soklet-final-schema-golden", "3.6.0-SNAPSHOT").build())
				.tool(tool)
				.tool(inputResponsesTool)
				.build();
		McpServer server = McpServer.withPort(0)
				.host("127.0.0.1")
				.handlerResolver(McpHandlerResolver.fromEndpoints(List.of(endpoint)))
				.requestAdmissionPolicy(McpRequestAdmissionPolicy.acceptAllInstance())
				.toolRateLimiter(context -> McpRateLimitDecision.fromAllowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of("127.0.0.1"))
				.build();

		try {
			server.start();
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
			server.stop();
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
				.jsonArguments()
				.handler((request, call, features) -> {
					if (request.getRequestState().isEmpty()) {
						Assertions.assertTrue(
								request.getInputResponses().asMap().isEmpty());
						return McpInputRequiredResult.builder()
								.inputRequest("approval", McpInputRequest
										.fromDeclaration(form,
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

					McpFrameworkRequestState state = Assertions.assertInstanceOf(
							McpFrameworkRequestState.class,
							request.getRequestState().orElseThrow());
					McpJsonObject stateValue = Assertions.assertInstanceOf(
							McpJsonObject.class, state.value());
					Assertions.assertEquals("awaiting-approval",
							Assertions.assertInstanceOf(McpJsonString.class,
									stateValue.find("phase").orElseThrow()).value());
					Assertions.assertEquals("phase-5-protected-state",
							Assertions.assertInstanceOf(McpJsonString.class,
									stateValue.find("fixture").orElseThrow()).value());
					McpJsonObject approval = Assertions.assertInstanceOf(
							McpJsonObject.class, request.getInputResponses()
									.find("approval").orElseThrow());
					Assertions.assertEquals("accept", Assertions.assertInstanceOf(
							McpJsonString.class,
							approval.find("action").orElseThrow()).value());
					McpJsonObject content = Assertions.assertInstanceOf(
							McpJsonObject.class,
							approval.find("content").orElseThrow());
					Assertions.assertEquals("approved",
							Assertions.assertInstanceOf(McpJsonString.class,
									content.find("answer").orElseThrow()).value());
					return McpCompleteResult.fromToolText(
							"protected request state accepted");
				})
				.mayRequestInput(form)
				.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp")
				.serverInformation(McpImplementation.withNameAndVersion(
						"soklet-final-schema-golden", "3.6.0-SNAPSHOT").build())
				.tool(tool)
				.build();
		McpServer server = McpServer.withPort(0)
				.host("127.0.0.1")
				.handlerResolver(McpHandlerResolver.fromEndpoints(List.of(endpoint)))
				.requestAdmissionPolicy(McpRequestAdmissionPolicy.acceptAllInstance())
				.toolRateLimiter(context -> McpRateLimitDecision.fromAllowed())
				.protectionConfig(McpProtectionConfig.withRequestStateProtector(
						new DeterministicGoldenRequestStateProtector()).build())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of("127.0.0.1"))
				.build();

		try {
			server.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			assertProtectedStateExchange(port,
					"phase-5/protected-state-initial-request.json",
					"phase-5/protected-state-initial-response.json");
			assertProtectedStateExchange(port,
					"phase-5/protected-state-retry-request.json",
					"phase-5/protected-state-retry-response.json");
		} finally {
			server.stop();
		}
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

	private static String fixture(String filename) throws Exception {
		String text = Files.readString(GOLDEN_ROOT.resolve(filename), StandardCharsets.UTF_8);
		Assertions.assertFalse(text.contains("\r"), filename + " must use LF");
		Assertions.assertTrue(text.endsWith("\n"), filename + " must end with LF");
		Assertions.assertFalse(text.substring(0, text.length() - 1).contains("\n"),
				filename + " must contain one compact JSON line");
		return text.substring(0, text.length() - 1);
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
