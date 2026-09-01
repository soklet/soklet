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

import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exact-inventory behavior contract for application-authored content metadata.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpMetadataBuilderReservedNamespaceTests {
	private static final Path INVENTORY = Path.of("api", "mcp",
			"mcp-metadata-builder-inventory.json");
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String RESERVED_KEY = "dev.mcp/handlerSecret";
	private static final String SECRET_VALUE = "handler-output-secret";
	private static final URI RESOURCE_URI = URI.create("test://reserved-metadata");
	private static final List<String> RESERVED_PREFIX_KEYS = List.of(
			"com.mcp/name",
			"com.mcp.example/name",
			"org.modelcontextprotocol/name",
			"org.modelcontextprotocol.example/name");
	private static final Map<String, MetadataAdapter> ADAPTERS = Map.ofEntries(
			Map.entry(metadataMethod("McpAudioContent"), metadata ->
					McpAudioContent.withDataAndMimeType(new byte[] { 1 },
							"audio/wav").metadata(metadata).build().getMetadata()),
			Map.entry(metadataMethod("McpBlobResourceContents"), metadata ->
					McpBlobResourceContents.withUriAndData(
							URI.create("test://adapter/blob"), new byte[] { 1 })
							.metadata(metadata).build().getMetadata()),
			Map.entry(metadataMethod("McpEmbeddedResource"), metadata ->
					McpEmbeddedResource.withResource(McpTextResourceContents
							.withUriAndText(URI.create("test://adapter/embedded"),
									"embedded").build())
							.metadata(metadata).build().getMetadata()),
			Map.entry(metadataMethod("McpImageContent"), metadata ->
					McpImageContent.withDataAndMimeType(new byte[] { 1 },
							"image/png").metadata(metadata).build().getMetadata()),
			Map.entry(metadataMethod("McpResourceLink"), metadata ->
					McpResourceLink.withUriAndName(
							URI.create("test://adapter/link"), "link")
							.metadata(metadata).build().getMetadata()),
			Map.entry(metadataMethod("McpTextContent"), metadata ->
					McpTextContent.withText("text").metadata(metadata)
							.build().getMetadata()),
			Map.entry(metadataMethod("McpTextResourceContents"), metadata ->
					McpTextResourceContents.withUriAndText(
							URI.create("test://adapter/text"), "text")
							.metadata(metadata).build().getMetadata()));

	@FunctionalInterface
	private interface MetadataAdapter {
		McpJsonObject build(McpJsonObject metadata);
	}

	@Test
	public void behaviorAdapterKeysExactlyMatchGeneratedInventory()
			throws IOException {
		Set<String> inventoryKeys = inventoryKeys();
		Assertions.assertEquals(7, inventoryKeys.size());
		Assertions.assertEquals(inventoryKeys, ADAPTERS.keySet(),
				"The exact-key behavior adapter drifted from the generated inventory");
	}

	@Test
	public void everyInventoriedBuilderRejectsEveryReservedPrefixAtConstruction() {
		for (Map.Entry<String, MetadataAdapter> adapter : ADAPTERS.entrySet()) {
			for (String key : RESERVED_PREFIX_KEYS) {
				McpJsonObject metadata = McpJsonObject.builder()
						.put(key, SECRET_VALUE).build();
				IllegalArgumentException exception = Assertions.assertThrows(
						IllegalArgumentException.class,
						() -> adapter.getValue().build(metadata),
						adapter.getKey() + " must reject " + key);
				Assertions.assertTrue(exception.getMessage()
						.startsWith("Application metadata must not use an MCP-reserved prefix:"),
						exception::getMessage);
			}
		}
	}

	@Test
	public void everyInventoriedBuilderPreservesEmptyAndApplicationMetadata() {
		McpJsonObject legal = McpJsonObject.builder()
				.put("com.example.mcp/application", "allowed")
				.put("com.example.modelcontextprotocol/application", true)
				.put("mcp.example/application", 1)
				.put("modelcontextprotocol.example/application", "allowed")
				.build();
		for (Map.Entry<String, MetadataAdapter> adapter : ADAPTERS.entrySet()) {
			Assertions.assertSame(McpJsonObject.emptyInstance(),
					adapter.getValue().build(McpJsonObject.emptyInstance()),
					adapter.getKey());
			Assertions.assertSame(legal, adapter.getValue().build(legal),
					adapter.getKey());
		}
	}

	@Test
	public void nestedToolPromptAndResourceOutputRejectBeforeOutputConstruction() {
		McpJsonObject reserved = reservedMetadata();
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpToolOutput.builder().content(McpEmbeddedResource.withResource(
						McpTextResourceContents.withUriAndText(
								URI.create("test://nested/tool-text"), "secret")
								.metadata(reserved).build()).build()).build());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpToolOutput.builder().content(McpEmbeddedResource.withResource(
						McpBlobResourceContents.withUriAndData(
								URI.create("test://nested/tool-blob"), new byte[] { 1 })
								.metadata(reserved).build()).build()).build());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpPromptOutput.builder().message(
						McpPromptMessage.fromAssistantContent(McpTextContent
								.withText("secret").metadata(reserved).build())).build());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpResourceOutput.builder().content(McpTextResourceContents
						.withUriAndText(RESOURCE_URI, "secret")
						.metadata(reserved).build()).build());
	}

	@Test
	public void handlerFailuresProduceOnlyGenericToolPromptAndResourceErrors()
			throws Exception {
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"metadata-reserved-namespace-test",
						"4.0.0").build())
				.tool(invalidNestedTextTool())
				.tool(invalidNestedBlobTool())
				.prompt(invalidPrompt())
				.resource(invalidResource())
				.build();
		McpServer server = McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			assertGenericFailure(exchange(port, "tool-text", "tools/call",
					"reserved.nested-text", ",\"name\":\"reserved.nested-text\","
							+ "\"arguments\":{}"), "tool-text");
			assertGenericFailure(exchange(port, "tool-blob", "tools/call",
					"reserved.nested-blob", ",\"name\":\"reserved.nested-blob\","
							+ "\"arguments\":{}"), "tool-blob");
			assertGenericFailure(exchange(port, "prompt", "prompts/get",
					"reserved.prompt", ",\"name\":\"reserved.prompt\","
							+ "\"arguments\":{}"), "prompt");
			assertGenericFailure(exchange(port, "resource", "resources/read",
					RESOURCE_URI.toString(), ",\"uri\":\"" + RESOURCE_URI + "\""),
					"resource");
		} finally {
			soklet.close();
		}
	}

	private static Soklet managedSoklet(McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.build());
	}

	private static McpToolRegistration<McpJsonObject> invalidNestedTextTool() {
		return McpToolRegistration.withName("reserved.nested-text")
				.jsonArguments()
				.handler((request, arguments, features) ->
						McpCompleteResult.fromToolOutput(McpToolOutput.builder()
								.content(McpEmbeddedResource.withResource(
										McpTextResourceContents.withUriAndText(
												URI.create("test://handler/tool-text"),
												SECRET_VALUE)
												.metadata(reservedMetadata()).build())
										.build()).build()))
				.build();
	}

	private static McpToolRegistration<McpJsonObject> invalidNestedBlobTool() {
		return McpToolRegistration.withName("reserved.nested-blob")
				.jsonArguments()
				.handler((request, arguments, features) ->
						McpCompleteResult.fromToolOutput(McpToolOutput.builder()
								.content(McpEmbeddedResource.withResource(
										McpBlobResourceContents.withUriAndData(
												URI.create("test://handler/tool-blob"),
												SECRET_VALUE.getBytes(StandardCharsets.UTF_8))
												.metadata(reservedMetadata()).build())
										.build()).build()))
				.build();
	}

	private static McpPromptRegistration invalidPrompt() {
		return McpPromptRegistration.withName("reserved.prompt")
				.handler((request, prompt, features) ->
						McpCompleteResult.fromPromptOutput(McpPromptOutput.builder()
								.message(McpPromptMessage.fromAssistantContent(
										McpTextContent.withText(SECRET_VALUE)
												.metadata(reservedMetadata()).build()))
								.build()))
				.build();
	}

	private static McpResourceRegistration invalidResource() {
		return McpResourceRegistration.withUriAndName(RESOURCE_URI,
				"Reserved metadata resource")
				.handler((request, resource, features) ->
						McpCompleteResult.fromResourceOutput(McpResourceOutput.builder()
								.content(McpTextResourceContents.withUriAndText(
										resource.getUri(), SECRET_VALUE)
										.metadata(reservedMetadata()).build())
								.build()))
				.build();
	}

	private static HttpResponse<String> exchange(int port, String id,
			String method, String name, String additionalParameters)
			throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}"
				+ additionalParameters + "}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method)
				.header("Mcp-Name", name)
				.POST(HttpRequest.BodyPublishers.ofString(body,
						StandardCharsets.UTF_8))
				.build();
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request, HttpResponse.BodyHandlers.ofString(
						StandardCharsets.UTF_8));
	}

	private static void assertGenericFailure(HttpResponse<String> response,
			String id) {
		Assertions.assertEquals(500, response.statusCode(), response.body());
		Assertions.assertEquals(JSON_MEDIA_TYPE,
				response.headers().firstValue("Content-Type").orElseThrow());
		Assertions.assertTrue(response.body().contains("\"id\":\"" + id + "\""),
				response.body());
		Assertions.assertTrue(response.body().contains("\"code\":-32603"),
				response.body());
		Assertions.assertTrue(response.body()
				.contains("\"message\":\"Internal error\""), response.body());
		for (String forbidden : List.of(
				RESERVED_KEY,
				SECRET_VALUE,
				"MCP-reserved prefix",
				"IllegalArgumentException",
				"\"result\":",
				"\"resultType\":",
				"\"content\":",
				"\"messages\":",
				"\"contents\":")) {
			Assertions.assertFalse(response.body().contains(forbidden),
					() -> "Leaked handler output or exception detail: " + response.body());
		}
	}

	private static Set<String> inventoryKeys() throws IOException {
		McpJsonValue parsed = new McpJsonCodec(McpJsonLimits.productionDefaults())
				.parse(Files.readAllBytes(INVENTORY));
		com.soklet.internal.mcp.protocol.McpJsonObject root =
				Assertions.assertInstanceOf(
						com.soklet.internal.mcp.protocol.McpJsonObject.class, parsed);
		McpJsonArray builders = Assertions.assertInstanceOf(McpJsonArray.class,
				root.members().get("builders"));
		Set<String> methods = new LinkedHashSet<>();
		for (McpJsonValue value : builders.values()) {
			com.soklet.internal.mcp.protocol.McpJsonObject row =
					Assertions.assertInstanceOf(
							com.soklet.internal.mcp.protocol.McpJsonObject.class,
							value);
			String method = Assertions.assertInstanceOf(McpJsonString.class,
					row.members().get("metadataMethod")).value();
			Assertions.assertTrue(methods.add(method),
					() -> "Duplicate inventory behavior key " + method);
		}
		return Set.copyOf(methods);
	}

	private static McpJsonObject reservedMetadata() {
		return McpJsonObject.builder().put(RESERVED_KEY, SECRET_VALUE).build();
	}

	private static String metadataMethod(String owner) {
		return "M:com/soklet/" + owner
				+ "$Builder#metadata(Lcom/soklet/McpJsonObject;)Lcom/soklet/"
				+ owner + "$Builder;";
	}
}
