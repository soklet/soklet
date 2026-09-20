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

import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Apps advertisement is derived from immutable declarations, not handlers. */
@ThreadSafe
class McpAppCapabilityRegistryTests {
	@Test
	void standaloneExactUiResourceAdvertisesCanonicalMimeCapability() {
		for (String mimeType : List.of("text/html;profile=mcp-app",
				" TEXT / HTML ; PROFILE = \"mcp-app\" ")) {
			McpServerCapabilityRegistry registry = McpServerCapabilityRegistry
					.fromEndpoint(endpoint().exactResource(resource("ui://test/view", mimeType))
							.build());
			assertEquals(new McpJsonObject(Map.of("mimeTypes", new McpJsonArray(
						List.of(new McpJsonString("text/html;profile=mcp-app"))))),
					registry.capabilities().extensions().get("io.modelcontextprotocol/ui"));
			assertFalse(registry.hasAppTools());
		}
	}

	@Test
	void ordinaryOrMalformedResourcesAndTemplatesDoNotAdvertiseApps() {
		for (String mimeType : List.of("text/html", "text/html;profile=MCP-APP",
				"text/html;profile=mcp-app;charset=utf-8", "legacy arbitrary mime"))
			assertFalse(McpServerCapabilityRegistry.fromEndpoint(endpoint()
					.exactResource(resource("ui://test/view", mimeType)).build())
					.capabilities().extensions().containsKey("io.modelcontextprotocol/ui"));
		assertFalse(McpServerCapabilityRegistry.fromEndpoint(endpoint()
				.exactResource(resource("https://test/view", "text/html;profile=mcp-app"))
				.build()).capabilities().extensions().containsKey("io.modelcontextprotocol/ui"));
		assertFalse(McpServerCapabilityRegistry.fromEndpoint(endpoint()
				.resourceTemplate(
						new McpNormalizedResourceTemplateDescriptor("ui://test/{view}", "view",
								new McpJsonObject(Map.of("mimeType",
										new McpJsonString("text/html;profile=mcp-app"))),
								McpJsonObject.empty(), McpResourceCachePolicy.privateNoCache()),
						McpInputRequestPlan.empty())
				.build()).capabilities().extensions().containsKey("io.modelcontextprotocol/ui"));
	}

	@Test
	void recognizedToolMetadataAdvertisesEvenWithoutResourceAssociation() {
		for (McpJsonObject ui : List.of(new McpJsonObject(Map.of("visibility",
				new McpJsonArray(List.of()))), new McpJsonObject(Map.of("visibility",
				new McpJsonArray(List.of(new McpJsonString("model"))))))) {
			McpServerCapabilityRegistry registry = McpServerCapabilityRegistry.fromEndpoint(
					endpoint().tool(tool(ui)).build());
			assertTrue(registry.hasAppTools());
			assertTrue(registry.capabilities().extensions().containsKey("io.modelcontextprotocol/ui"));
		}
		McpServerCapabilityRegistry ordinary = McpServerCapabilityRegistry.fromEndpoint(
				endpoint().tool(tool(new McpJsonObject(Map.of("vendor/example",
						new McpJsonString("retained"))))).build());
		assertFalse(ordinary.hasAppTools());
		assertFalse(ordinary.capabilities().extensions().containsKey("io.modelcontextprotocol/ui"));
	}

	@Test
	void fallbackPreservesUnknownMetadataAndDoesNotMutateCanonicalDescriptor() {
		McpJsonObject ui = new McpJsonObject(Map.of("resourceUri",
				new McpJsonString("ui://test/view"), "vendor/example",
				new McpJsonString("retained")));
		McpServerCapabilityRegistry registry = McpServerCapabilityRegistry.fromEndpoint(
				endpoint().tool(tool(ui)).build());
		McpJsonObject fallback = firstTool(registry.toolsListResult(Set.of("view"), false));
		assertEquals(new McpJsonObject(Map.of("ui", new McpJsonObject(Map.of(
				"vendor/example", new McpJsonString("retained"))))),
				fallback.members().get("_meta"));
		assertEquals(new McpJsonObject(Map.of("ui", ui)), firstTool(
				registry.toolsListResult(Set.of("view"), true)).members().get("_meta"));
		assertEquals(new McpJsonArray(List.of()), registry.toolsListResult(Set.of(), true)
				.toJsonObject().members().get("tools"));
		assertEquals(fallback, firstTool(registry.toolsListResult(Set.of("view"), false)));
	}

	private static McpJsonObject firstTool(McpWireResult result) {
		return (McpJsonObject) ((McpJsonArray) result.toJsonObject().members()
				.get("tools")).values().get(0);
	}

	private static McpNormalizedOperation tool(McpJsonObject ui) {
		return McpNormalizedOperation.tool(new McpNormalizedToolDescriptor("view",
				new McpJsonObject(Map.of("type", new McpJsonString("object"))),
				Optional.empty(), McpJsonObject.empty(),
				new McpJsonObject(Map.of("ui", ui))), McpInputRequestPlan.empty(),
				McpMirroredHeaderPlan.empty());
	}

	private static McpNormalizedResourceDescriptor resource(String uri, String mimeType) {
		return new McpNormalizedResourceDescriptor(
				uri, "view", new McpJsonObject(Map.of("mimeType", new McpJsonString(mimeType))),
				McpJsonObject.empty(), McpResourceCachePolicy.privateNoCache());
	}

	private static McpNormalizedEndpoint.Builder endpoint() {
		return McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion("apps", "test"));
	}
}
