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
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.soklet.McpLegacySessionTransportPublicRuntimeTests.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact icon projection, private declaration failures, and recovery on real listeners. */
@Timeout(60)
class McpIconPublicRuntimeTests {
	private static final Set<McpProtocolVersion> VERSIONS = Set.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25,
			McpProtocolVersion.V2026_07_28);
	private static final String CANARY = "private-icon-canary";
	private static final URI SOURCE = URI.create("HTTPS://Icons.Example/a/../icon%2f.png?theme=dark#preview");
	private static final URI RESOURCE = URI.create("test://icons/resource");
	private static final String MIME_TYPE = "IMAGE/SVG+XML; Name=\"Quoted; value\"";
	private static final String ICONS_JSON = "\"icons\":[{\"src\":\"" + SOURCE
			+ "\",\"mimeType\":\"IMAGE/SVG+XML; Name=\\\"Quoted; value\\\"\","
			+ "\"sizes\":[\"001x02\",\"any\",\"001x02\"],\"theme\":\"dark\"},"
			+ "{\"src\":\"data:image/png;base64,AA==\"}]";

	@TestFactory
	Stream<DynamicTest> iconsKeepExactValuesAndRespectEachRevisionProjection() {
		return VERSIONS.stream().map(protocolVersion -> DynamicTest.dynamicTest(
				protocolVersion.getWireValue(), () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> verifyIcons(protocolVersion))));
	}

	private void verifyIcons(McpProtocolVersion protocolVersion) throws Exception {
		McpIcon declared = McpIcon.withSource(SOURCE).mimeType(MIME_TYPE)
				.sizes(List.of("001x02", "any", "001x02")).theme(McpIconTheme.DARK).build();
		McpIcon data = McpIcon.withSource(URI.create("data:image/png;base64,AA==")).build();
		List<McpIcon> icons = List.of(declared, data);
		AtomicReference<String> mode = new AtomicReference<>("valid");
		AtomicInteger handlerCalls = new AtomicInteger();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName("icons", VERSIONS)
				.jsonObjectArguments()
				.handler((requestContext, toolArguments, invocationFeatures) -> {
					handlerCalls.incrementAndGet();
					McpIcon first = switch (mode.get()) {
						case "relative" -> McpIcon.withSource(URI.create(CANARY + ".png")).build();
						case "mime" -> McpIcon.withSource(SOURCE).mimeType(CANARY + "/").build();
						case "size" -> McpIcon.withSource(SOURCE).sizes(List.of("48x48", CANARY)).build();
						default -> declared;
					};
					return McpCompleteResult.fromToolOutput(McpToolOutput.builder().content(List.of(
							McpResourceLink.withUriAndName(RESOURCE, "linked")
									.addIcon(first).addIcon(data).build())).build());
				}).icons(icons).build();
		McpPromptRegistration prompt = McpPromptRegistration.withName("icons", VERSIONS)
				.handler((requestContext, promptArguments, invocationFeatures) ->
						McpCompleteResult.fromPromptOutput(McpPromptOutput.fromMessages(
								McpPromptMessage.fromUserText("prompt")))).icons(icons).build();
		McpResourceRegistration resource = McpResourceRegistration.withUriAndName(RESOURCE, "icons", VERSIONS)
				.handler((requestContext, resourceReadContext, invocationFeatures) ->
						McpCompleteResult.fromResourceOutput(McpResourceOutput.fromContent(
								McpTextResourceContents.withUriAndText(resourceReadContext.getUri(), "resource").build()))).icons(icons).build();
		McpResourceRegistration template = McpResourceRegistration.withUriTemplateAndName("test://icons/{id}", "template", VERSIONS)
				.handler((requestContext, resourceReadContext, invocationFeatures) ->
						McpCompleteResult.fromResourceOutput(McpResourceOutput.fromContent(
								McpTextResourceContents.withUriAndText(resourceReadContext.getUri(), "template").build()))).icons(icons).build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("icon-test", "1").build(), VERSIONS)
				.toolRegistrations(List.of(tool)).promptRegistrations(List.of(prompt))
				.resourceRegistrations(List.of(resource, template)).build();
		McpServer server = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.toolRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed()).build();
		try (Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).build())) {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			for (String method : List.of("tools/list", "prompts/list", "resources/list", "resources/templates/list"))
				assertIcons(call(port, protocolVersion, method), protocolVersion);
			Response initial = call(port, protocolVersion, "tools/call");
			assertIcons(initial, protocolVersion);
			assertTrue(initial.body().contains("\"type\":\"resource_link\""), initial.body());
			assertTrue(initial.body().contains("\"name\":\"linked\""), initial.body());
			for (String invalid : List.of("relative", "mime", "size")) {
				mode.set(invalid);
				Response failure = call(port, protocolVersion, "tools/call");
				assertEquals(protocolVersion == McpProtocolVersion.V2026_07_28 ? 500 : 200, failure.status(), failure.body());
				assertTrue(failure.body().contains("\"code\":-32603"), failure.body());
				for (String privateText : List.of(CANARY, "absolute URIs", "MIME type", "WxH", "\"content\"", "\"icons\""))
					assertFalse(failure.body().contains(privateText), failure.body());
			}
			mode.set("valid");
			Response recovered = call(port, protocolVersion, "tools/call");
			assertIcons(recovered, protocolVersion);
			assertTrue(recovered.body().contains("\"type\":\"resource_link\""), recovered.body());
			assertEquals(5, handlerCalls.get());
			assertEquals(icons, tool.getIcons());
			assertEquals(SOURCE, declared.getSource());
			assertEquals(MIME_TYPE, declared.getMimeType().orElseThrow());
			assertEquals(List.of("001x02", "any", "001x02"), declared.getSizes());
		}
	}

	private static void assertIcons(Response response, McpProtocolVersion protocolVersion) {
		assertEquals(200, response.status(), response.body());
		assertFalse(response.body().contains("\"error\""), response.body());
		if (protocolVersion == McpProtocolVersion.V2025_06_18)
			assertFalse(response.body().contains("\"icons\":"), response.body());
		else
			assertTrue(response.body().contains(ICONS_JSON), response.body());
	}

	private static Response call(int port, McpProtocolVersion protocolVersion, String method) throws Exception {
		boolean toolCall = method.equals("tools/call");
		String metadata = protocolVersion == McpProtocolVersion.V2026_07_28
				? "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
						+ "\"io.modelcontextprotocol/clientCapabilities\":{}}" : "";
		String arguments = toolCall ? "\"name\":\"icons\",\"arguments\":{}" : "";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"icon-test\",\"method\":\"" + method
				+ "\",\"params\":{" + arguments + (!arguments.isEmpty() && !metadata.isEmpty() ? "," : "") + metadata + "}}";
		List<HeaderValue> headers = new ArrayList<>();
		headers.add(new HeaderValue("MCP-Protocol-Version", protocolVersion.getWireValue()));
		if (protocolVersion == McpProtocolVersion.V2026_07_28) {
			headers.add(new HeaderValue("Mcp-Method", method));
			if (toolCall)
				headers.add(new HeaderValue("Mcp-Name", "icons"));
		}
		try (RawClient client = new RawClient(port, "POST", "/mcp", body, headers)) {
			Head head = client.readHead();
			return new Response(head.status(), client.readBody(head));
		}
	}

	private record Response(int status, String body) {}
}
