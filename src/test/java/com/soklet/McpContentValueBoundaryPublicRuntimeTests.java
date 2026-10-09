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

/** Content validation, binary wire limits, and recovery across all revisions. */
@Timeout(60)
class McpContentValueBoundaryPublicRuntimeTests {
	private static final Set<McpProtocolVersion> VERSIONS = Set.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25,
			McpProtocolVersion.V2026_07_28);
	private static final String CANARY = "private-content-canary";
	private static final String IMAGE_MIME_TYPE = "IMAGE/PNG; Name=\"Quoted; value\"";
	private static final String AUDIO_MIME_TYPE = "audio/x-vendor; Codec=custom";

	@TestFactory
	Stream<DynamicTest> contentBoundariesFailPrivatelyAndRecoverForEveryRevision() {
		return VERSIONS.stream().map(protocolVersion -> DynamicTest.dynamicTest(
				protocolVersion.getWireValue(), () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> verifyBoundaries(protocolVersion))));
	}

	private void verifyBoundaries(McpProtocolVersion protocolVersion) throws Exception {
		AtomicReference<String> mode = new AtomicReference<>("text");
		AtomicInteger bytes = new AtomicInteger(786_432);
		AtomicInteger handlerCalls = new AtomicInteger();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName("content", VERSIONS)
				.jsonObjectArguments().handler((requestContext, toolArguments, invocationFeatures) -> {
					handlerCalls.incrementAndGet();
					McpContentBlock content = switch (mode.get()) {
						case "image" -> McpImageContent.withDataAndMimeType(new byte[bytes.get()], IMAGE_MIME_TYPE).build();
						case "audio" -> McpAudioContent.withDataAndMimeType(new byte[bytes.get()], AUDIO_MIME_TYPE).build();
						case "blob" -> McpEmbeddedResource.withResource(McpBlobResourceContents
								.withUriAndData(URI.create("test://content/blob"), new byte[bytes.get()]).build()).build();
						case "invalid-text" -> McpTextContent.fromText(CANARY + "\uD800");
						case "invalid-mime" -> McpImageContent.withDataAndMimeType(new byte[0], CANARY + "/").build();
						default -> McpTextContent.fromText("recovered 😀\n");
					};
					return McpCompleteResult.fromToolOutput(McpToolOutput.builder().content(List.of(content)).build());
				}).build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("content-boundary-test", "1").build(), VERSIONS)
				.toolRegistrations(List.of(tool)).build();
		McpServer server = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.toolRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed()).build();
		try (Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).build())) {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			for (String kind : List.of("image", "audio", "blob")) {
				mode.set(kind);
				bytes.set(786_432);
				Response accepted = call(port, protocolVersion);
				assertEquals(200, accepted.status());
				assertFalse(accepted.body().contains("\"error\""));
				String field = kind.equals("blob") ? "\"blob\":\"" : "\"data\":\"";
				int start = accepted.body().indexOf(field);
				assertTrue(start >= 0, kind);
				start += field.length();
				assertEquals(1_048_576, accepted.body().indexOf('"', start) - start, kind);
				assertTrue(accepted.body().substring(start, start + 1_048_576).chars().allMatch(character -> character == 'A'), kind);
				if (kind.equals("image"))
					assertTrue(accepted.body().contains("\"mimeType\":\"IMAGE/PNG; Name=\\\"Quoted; value\\\"\""));
				if (kind.equals("audio"))
					assertTrue(accepted.body().contains("\"mimeType\":\"" + AUDIO_MIME_TYPE + "\""));
				bytes.set(786_433);
				assertPrivateFailure(call(port, protocolVersion), protocolVersion);
			}
			for (String invalid : List.of("invalid-text", "invalid-mime")) {
				mode.set(invalid);
				assertPrivateFailure(call(port, protocolVersion), protocolVersion);
			}
			mode.set("text");
			Response recovered = call(port, protocolVersion);
			assertEquals(200, recovered.status(), recovered.body());
			assertTrue(recovered.body().contains("recovered 😀\\n"), recovered.body());
			assertFalse(recovered.body().contains("\"error\""), recovered.body());
			assertEquals(9, handlerCalls.get());
		}
	}

	private static void assertPrivateFailure(Response response, McpProtocolVersion protocolVersion) {
		assertEquals(protocolVersion == McpProtocolVersion.V2026_07_28 ? 500 : 200, response.status(), response.body());
		assertTrue(response.body().contains("\"code\":-32603"), response.body());
		assertFalse(response.body().contains("\"content\""), response.body());
		assertFalse(response.body().contains("\"isError\""), response.body());
		for (String privateText : List.of(CANARY, "surrogate", "MIME type", "Base64", "786", "1048576"))
			assertFalse(response.body().contains(privateText), response.body());
	}

	private static Response call(int port, McpProtocolVersion protocolVersion) throws Exception {
		String metadata = protocolVersion == McpProtocolVersion.V2026_07_28
				? ",\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
						+ "\"io.modelcontextprotocol/clientCapabilities\":{}}" : "";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"content-test\",\"method\":\"tools/call\","
				+ "\"params\":{\"name\":\"content\",\"arguments\":{}" + metadata + "}}";
		List<HeaderValue> headers = new ArrayList<>();
		headers.add(new HeaderValue("MCP-Protocol-Version", protocolVersion.getWireValue()));
		if (protocolVersion == McpProtocolVersion.V2026_07_28) {
			headers.add(new HeaderValue("Mcp-Method", "tools/call"));
			headers.add(new HeaderValue("Mcp-Name", "content"));
		}
		try (RawClient client = new RawClient(port, "POST", "/mcp", body, headers)) {
			Head head = client.readHead();
			return new Response(head.status(), client.readBody(head));
		}
	}

	private record Response(int status, String body) {}
}
