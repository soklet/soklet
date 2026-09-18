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
import com.soklet.McpLocalizationContext;
import com.soklet.McpRequestContext;
import com.soklet.McpRequestOutcome;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.NotThreadSafe;
import java.lang.reflect.Proxy;
import java.time.Duration;
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

	@Test
	public void explicit_catalog_access_bypasses_unfiltered_preflight_and_serves_visible_entries()
			throws Exception {
		String largeDescription = "x".repeat(TEST_OUTPUT_LIMIT * 2);
		McpJsonObject descriptorFields = new McpJsonObject(Map.of(
				"description", new McpJsonString(largeDescription)));
		McpJsonObject objectSchema = new McpJsonObject(Map.of(
				"type", new McpJsonString("object")));
		McpNormalizedToolDescriptor hiddenTool = new McpNormalizedToolDescriptor(
				"hidden-tool", objectSchema, Optional.empty(), descriptorFields,
				McpJsonObject.empty());
		McpNormalizedPromptDescriptor hiddenPrompt =
				new McpNormalizedPromptDescriptor("hidden-prompt", List.of(),
						descriptorFields, McpJsonObject.empty());
		McpNormalizedEndpoint endpoint = endpointBuilder()
				.tool(McpNormalizedOperation.named("visible-tool"))
				.tool(McpNormalizedOperation.tool(hiddenTool,
						McpMirroredHeaderPlan.empty()))
				.prompt(McpNormalizedOperation.named("visible-prompt"))
				.prompt(hiddenPrompt)
				.catalogAccessAdapter(ignored ->
						new McpServerRuntimeBridge.CatalogAccessSession() {
							@Override
							public boolean isToolAccessible(@NonNull String toolName) {
								return "visible-tool".equals(toolName);
							}

							@Override
							public boolean isPromptAccessible(
									@NonNull String promptName) {
								return "visible-prompt".equals(promptName);
							}

							@Override
							@NonNull
							public Optional<@NonNull McpLocalizationContext>
									localizationContext() {
								return Optional.empty();
							}
						})
				.build();

		try (McpHttpServerRuntime runtime = runtime(endpoint)) {
			int port = runtime.start().getPort();
			String tools = listResponse(port, "tools-list", "tools/list");
			Assertions.assertTrue(tools.contains("visible-tool"), tools);
			Assertions.assertFalse(tools.contains("hidden-tool"), tools);

			String prompts = listResponse(port, "prompts-list", "prompts/list");
			Assertions.assertTrue(prompts.contains("visible-prompt"), prompts);
			Assertions.assertFalse(prompts.contains("hidden-prompt"), prompts);
		}
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
						"static-preflight-test", "4.0.0"));
	}

	private static String listResponse(int port, String id, String method)
			throws Exception {
		try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcp(
				port, "\"" + id + "\"", method)) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			String body = client.readFixedBody(head);
			Assertions.assertEquals(200, head.status(), body);
			return body;
		}
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
		McpHttpEndpointPolicy endpointPolicy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(),
				ignored -> McpAdmissionDecision.acceptedAnonymous());
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(endpointPolicy,
				endpoint, McpApplicationRequestRouter.empty(),
				observationWithPublicContext());
		return new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0),
				List.of(binding), limits,
				McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM,
				McpApplicationHandlerExecutorFactory.production(), ignored -> {},
				ignored -> {});
	}

	private static McpRuntimeObservationSink observationWithPublicContext() {
		McpRequestContext context = (McpRequestContext) Proxy.newProxyInstance(
				McpRequestContext.class.getClassLoader(),
				new Class<?>[]{McpRequestContext.class},
				(proxy, method, arguments) -> {
					if (method.getReturnType() == Optional.class)
						return Optional.empty();
					if (method.getReturnType() == Map.class)
						return Map.of();
					if (method.getReturnType() == String.class)
						return "static-preflight-test";
					if (method.getReturnType() == boolean.class)
						return false;
					return null;
				});
		return ignored -> new McpRuntimeRequestObservation() {
			@Override
			@NonNull
			public Optional<@NonNull McpRequestContext> publicContext() {
				return Optional.of(context);
			}

			@Override
			public void didFinish(@NonNull McpRequestOutcome outcome,
					McpJsonRpcError error, @NonNull Duration duration,
					@NonNull List<@NonNull Throwable> throwables) {
			}
		};
	}
}
