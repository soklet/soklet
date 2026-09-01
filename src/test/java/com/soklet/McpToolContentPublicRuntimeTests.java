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

/**
 * Black-box real-listener coverage for public MCP tool content blocks.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpToolContentPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String IMAGE_TOOL = "test_image_content";
	private static final String AUDIO_TOOL = "test_audio_content";
	private static final String EMBEDDED_RESOURCE_TOOL = "test_embedded_resource";
	private static final String MIXED_CONTENT_TOOL = "test_multiple_content_types";

	@Test
	public void toolContentBlocksPreserveExactWireValuesAndOrderThroughPublicListener()
			throws Exception {
		McpToolRegistration<McpJsonObject> imageTool = tool(IMAGE_TOOL,
				McpToolOutput.builder()
						.content(McpImageContent.withDataAndMimeType(
								new byte[] { 0, 1, 2, (byte) 255 }, "image/png")
								.build())
						.build());
		McpToolRegistration<McpJsonObject> audioTool = tool(AUDIO_TOOL,
				McpToolOutput.builder()
						.content(McpAudioContent.withDataAndMimeType(
								new byte[] { 'R', 'I', 'F', 'F' }, "audio/wav")
								.build())
						.build());
		McpTextResourceContents embeddedContents = McpTextResourceContents
				.withUriAndText(URI.create("test://embedded/content"),
						"embedded \"payload\" 世界")
				.mimeType("text/plain; charset=utf-8")
				.build();
		McpToolRegistration<McpJsonObject> embeddedResourceTool = tool(
				EMBEDDED_RESOURCE_TOOL, McpToolOutput.builder()
						.content(McpEmbeddedResource.withResource(embeddedContents)
								.build())
						.build());
		McpTextResourceContents mixedResource = McpTextResourceContents
				.withUriAndText(URI.create("test://mixed/final"),
						"{\"status\":\"ok\"}")
				.mimeType("application/json")
				.build();
		McpToolRegistration<McpJsonObject> mixedContentTool = tool(
				MIXED_CONTENT_TOOL, McpToolOutput.builder()
						.content(McpTextContent.fromText("first"))
						.content(McpImageContent.withDataAndMimeType(
								new byte[] { 9, 8, 7 }, "image/gif").build())
						.content(McpEmbeddedResource.withResource(mixedResource).build())
						.build());
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"tool-content-public-runtime-test", "4.0.0").build())
				.tool(imageTool)
				.tool(audioTool)
				.tool(embeddedResourceTool)
				.tool(mixedContentTool)
				.build();
		McpServer server = McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();

			String image = call(port, "image", IMAGE_TOOL);
			assertContains(image, "\"content\":[{\"type\":\"image\","
					+ "\"data\":\"AAEC/w==\",\"mimeType\":\"image/png\"}]");
			assertSuccessfulToolOutput(image);

			String audio = call(port, "audio", AUDIO_TOOL);
			assertContains(audio, "\"content\":[{\"type\":\"audio\","
					+ "\"data\":\"UklGRg==\",\"mimeType\":\"audio/wav\"}]");
			assertSuccessfulToolOutput(audio);

			String embedded = call(port, "embedded", EMBEDDED_RESOURCE_TOOL);
			assertContains(embedded, "\"content\":[{\"type\":\"resource\","
					+ "\"resource\":{\"uri\":\"test://embedded/content\","
					+ "\"mimeType\":\"text/plain; charset=utf-8\","
					+ "\"text\":\"embedded \\\"payload\\\" 世界\"}}]");
			assertSuccessfulToolOutput(embedded);

			String mixed = call(port, "mixed", MIXED_CONTENT_TOOL);
			String textBlock = "{\"type\":\"text\",\"text\":\"first\"}";
			String imageBlock = "{\"type\":\"image\",\"data\":\"CQgH\","
					+ "\"mimeType\":\"image/gif\"}";
			String resourceBlock = "{\"type\":\"resource\",\"resource\":{"
					+ "\"uri\":\"test://mixed/final\","
					+ "\"mimeType\":\"application/json\","
					+ "\"text\":\"{\\\"status\\\":\\\"ok\\\"}\"}}";
			assertContains(mixed, "\"content\":[" + textBlock + ","
					+ imageBlock + "," + resourceBlock + "]");
			assertInOrder(mixed, textBlock, imageBlock, resourceBlock);
			assertSuccessfulToolOutput(mixed);
		} finally {
			owner.close();
		}
	}

	private static Soklet managedSoklet(McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.build());
	}

	private static McpToolRegistration<McpJsonObject> tool(String name,
			McpToolOutput output) {
		return McpToolRegistration.withName(name)
				.jsonArguments()
				.handler((request, arguments, features) ->
						McpCompleteResult.fromToolOutput(output))
				.description("Public runtime content fixture")
				.build();
	}

	private static String call(int port, String id, String toolName)
			throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"name\":\"" + toolName + "\",\"arguments\":{}}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", "tools/call")
				.header("Mcp-Name", toolName)
				.POST(HttpRequest.BodyPublishers.ofString(body,
						StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> response = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request, HttpResponse.BodyHandlers.ofString(
						StandardCharsets.UTF_8));
		Assertions.assertEquals(200, response.statusCode(), response.body());
		Assertions.assertEquals(JSON_MEDIA_TYPE,
				response.headers().firstValue("Content-Type").orElseThrow());
		assertContains(response.body(), "\"id\":\"" + id + "\"");
		return response.body();
	}

	private static void assertInOrder(String text, String... expected) {
		int previous = -1;
		for (String value : expected) {
			int index = text.indexOf(value);
			Assertions.assertTrue(index > previous, text);
			previous = index;
		}
	}

	private static void assertSuccessfulToolOutput(String body) {
		assertContains(body, "\"resultType\":\"complete\"");
		Assertions.assertFalse(body.contains("\"isError\""), body);
	}

	private static void assertContains(String actual, String expected) {
		Assertions.assertTrue(actual.contains(expected), () ->
				"Expected <" + actual + "> to contain <" + expected + ">.");
	}
}
