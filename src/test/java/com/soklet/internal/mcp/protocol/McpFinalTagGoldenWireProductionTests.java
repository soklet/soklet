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
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpHandlerResolver;
import com.soklet.McpImplementation;
import com.soklet.McpPromptArgumentDefinition;
import com.soklet.McpPromptMessage;
import com.soklet.McpPromptOutput;
import com.soklet.McpPromptRegistration;
import com.soklet.McpRequestAdmissionPolicy;
import com.soklet.McpResourceOutput;
import com.soklet.McpResourceRegistration;
import com.soklet.McpServer;
import com.soklet.McpTextResourceContents;
import com.soklet.McpTextContent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

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
}
