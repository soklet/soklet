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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tests construction-time output-bound preflight for framework-owned MCP
 * responses.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public class McpStaticResponsePreflightTests {
	private static final int TEST_OUTPUT_LIMIT = 1_024;

	@Test
	public void every_framework_owned_catalog_is_preflighted() {
		String largeDescription = "x".repeat(TEST_OUTPUT_LIMIT * 2);
		McpJsonObject descriptorFields = new McpJsonObject(Map.of(
				"description", new McpJsonString(largeDescription)));
		McpJsonObject objectSchema = new McpJsonObject(Map.of(
				"type", new McpJsonString("object")));

		McpNormalizedToolDescriptor tool = new McpNormalizedToolDescriptor(
				"large-tool", objectSchema, Optional.empty(), descriptorFields,
				McpJsonObject.empty());
		McpNormalizedPromptDescriptor prompt = new McpNormalizedPromptDescriptor(
				"large-prompt", List.of(), descriptorFields, McpJsonObject.empty());
		McpNormalizedResourceDescriptor resource =
				new McpNormalizedResourceDescriptor("catalog://large", "Large resource",
						descriptorFields, McpJsonObject.empty(),
						McpResourceCachePolicy.privateNoCache());
		McpNormalizedResourceTemplateDescriptor resourceTemplate =
				new McpNormalizedResourceTemplateDescriptor(
						"catalog://large/{id}", "Large resource template",
						descriptorFields, McpJsonObject.empty(),
						McpResourceCachePolicy.privateNoCache());

		assertPreflightFailure(endpointBuilder()
				.tool(McpNormalizedOperation.tool(tool, McpMirroredHeaderPlan.empty()))
				.build(), "tools/list");
		assertPreflightFailure(endpointBuilder().prompt(prompt).build(), "prompts/list");
		assertPreflightFailure(endpointBuilder().exactResource(resource).build(),
				"resources/list");
		assertPreflightFailure(endpointBuilder().resourceTemplate(resourceTemplate).build(),
				"resources/templates/list");
	}

	@Test
	public void discovery_is_preflighted_and_custom_resource_lists_remain_dynamic() {
		McpNormalizedEndpoint oversizedDiscovery = endpointBuilder()
				.instructions("x".repeat(TEST_OUTPUT_LIMIT * 2))
				.build();
		assertPreflightFailure(oversizedDiscovery, "server/discover");

		McpJsonObject descriptorFields = new McpJsonObject(Map.of(
				"description", new McpJsonString("x".repeat(TEST_OUTPUT_LIMIT * 2))));
		McpNormalizedResourceDescriptor resource =
				new McpNormalizedResourceDescriptor("catalog://dynamic", "Dynamic resource",
						descriptorFields, McpJsonObject.empty(),
						McpResourceCachePolicy.privateNoCache());
		McpNormalizedEndpoint customList = endpointBuilder()
				.exactResource(resource)
				.customResourceListHandler()
				.build();
		Assertions.assertDoesNotThrow(() -> {
			try (McpHttpServerRuntime ignored = runtime(customList)) {
				// Construction is the preflight boundary.
			}
		});
	}

	private static void assertPreflightFailure(McpNormalizedEndpoint endpoint,
			String method) {
		IllegalArgumentException exception = Assertions.assertThrows(
				IllegalArgumentException.class, () -> runtime(endpoint));
		Assertions.assertTrue(exception.getMessage().contains("'" + method + "'"),
				exception.getMessage());
		Assertions.assertTrue(exception.getMessage().contains(
				"maximum UTF-8 bytes: " + TEST_OUTPUT_LIMIT), exception.getMessage());
	}

	private static McpNormalizedEndpoint.Builder endpointBuilder() {
		return McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"static-preflight-test", "4.0.0-SNAPSHOT"));
	}

	private static McpHttpServerRuntime runtime(McpNormalizedEndpoint endpoint) {
		McpJsonLimits production = McpJsonLimits.productionDefaults();
		McpJsonLimits limits = new McpJsonLimits(production.maximumInputBytes(),
				production.maximumNestingDepth(),
				production.maximumTokenLengthInCharacters(),
				production.maximumStringLengthInCharacters(),
				production.maximumNumberLengthInCharacters(),
				production.maximumExponentMagnitude(), production.maximumNodeCount(),
				TEST_OUTPUT_LIMIT);
		return new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0),
				McpHttpEndpointPolicy.forDiscovery(CorsAuthorizer.rejectAllInstance(),
						ignored -> McpAdmissionDecision.acceptedAnonymous()),
				endpoint, limits, McpApplicationRequestRouter.empty(),
				McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM,
				McpApplicationHandlerExecutorFactory.production());
	}
}
