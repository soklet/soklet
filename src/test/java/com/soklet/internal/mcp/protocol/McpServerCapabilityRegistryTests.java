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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class McpServerCapabilityRegistryTests {
	@Test
	public void empty_registration_advertises_no_optional_server_capability() {
		McpServerCapabilityRegistry registry =
				McpServerCapabilityRegistry.fromEndpoint(endpointBuilder().build());

		Assertions.assertEquals(McpJsonObject.empty(),
				registry.capabilities().toJsonObject());
		Assertions.assertTrue(registry.tools().isEmpty());
		Assertions.assertTrue(registry.prompts().isEmpty());
		Assertions.assertTrue(registry.permitsResultType(McpResultType.COMPLETE));
		Assertions.assertTrue(registry.permitsResultType(McpResultType.INPUT_REQUIRED));
		Assertions.assertFalse(registry.permitsResultType(McpResultType.extension("task")));
	}

	@Test
	public void capability_registry_advertises_the_exact_registration_power_set() {
		for (int mask = 0; mask < 8; ++mask) {
			McpNormalizedEndpoint.Builder builder = endpointBuilder();
			java.util.LinkedHashSet<String> expectedCapabilities =
					new java.util.LinkedHashSet<>();

			if ((mask & 1) != 0) {
				builder.tool(McpNormalizedOperation.named("lookup"));
				expectedCapabilities.add("tools");
			}

			if ((mask & 2) != 0) {
				builder.prompt(McpNormalizedOperation.named("summarize"));
				expectedCapabilities.add("prompts");
			}

			if ((mask & 4) != 0) {
				builder.exactResource("catalog://items/1");
				expectedCapabilities.add("resources");
			}

			McpJsonObject capabilities = McpServerCapabilityRegistry
					.fromEndpoint(builder.build()).capabilities().toJsonObject();
			Assertions.assertEquals(expectedCapabilities, capabilities.members().keySet());
			Assertions.assertFalse(capabilities.members().containsKey("completions"));
			Assertions.assertFalse(capabilities.members().containsKey("experimental"));
			Assertions.assertFalse(capabilities.members().containsKey("extensions"));
			Assertions.assertFalse(capabilities.members().containsKey("logging"));
		}
	}

	@Test
	public void tool_and_prompt_catalogs_are_truthful_immutable_and_never_mutable() {
		McpNormalizedEndpoint.Builder builder = endpointBuilder()
				.tool(McpNormalizedOperation.named("lookup"))
				.prompt(McpNormalizedOperation.named("summarize"));
		McpNormalizedEndpoint endpoint = builder.build();
		builder.tool(McpNormalizedOperation.named("added-too-late"));
		McpServerCapabilityRegistry registry =
				McpServerCapabilityRegistry.fromEndpoint(endpoint);
		McpJsonObject capabilities = registry.capabilities().toJsonObject();

		Assertions.assertEquals(List.of("lookup"), registry.tools());
		Assertions.assertEquals(List.of("summarize"), registry.prompts());
		Assertions.assertEquals(Set.of("tools", "prompts"), capabilities.members().keySet());
		Assertions.assertEquals(McpJsonObject.empty(),
				capabilities.members().get("tools"));
		Assertions.assertEquals(McpJsonObject.empty(),
				capabilities.members().get("prompts"));
		Assertions.assertFalse(
				((McpJsonObject) capabilities.members().get("tools"))
						.members().containsKey("listChanged"));
		Assertions.assertFalse(
				((McpJsonObject) capabilities.members().get("prompts"))
						.members().containsKey("listChanged"));
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> registry.tools().add("mutation"));
	}

	@Test
	public void every_registered_resource_surface_derives_resources_capability() {
		List<McpNormalizedEndpoint> endpoints = List.of(
				endpointBuilder().exactResource("catalog://items/1").build(),
				endpointBuilder().resourceTemplate("catalog://items/{id}").build(),
				endpointBuilder().customResourceListHandler().build());

		for (McpNormalizedEndpoint endpoint : endpoints) {
			McpServerCapabilityRegistry registry =
					McpServerCapabilityRegistry.fromEndpoint(endpoint);
			Assertions.assertTrue(registry.capabilities().resources().isPresent());
			Assertions.assertEquals(McpJsonObject.empty(),
					registry.capabilities().resources().orElseThrow().toJsonObject());
		}
	}

	@Test
	public void resource_flags_derive_only_from_configured_publisher_notification_types() {
		McpServerCapabilityRegistry listChanged =
				McpServerCapabilityRegistry.fromEndpoint(endpointBuilder()
						.exactResource("catalog://items/1")
						.subscriptions(McpNormalizedSubscriptionConfiguration.supporting(
								McpResourceNotificationType.RESOURCES_LIST_CHANGED))
						.build());
		McpServerCapabilityRegistry updated =
				McpServerCapabilityRegistry.fromEndpoint(endpointBuilder()
						.exactResource("catalog://items/1")
						.subscriptions(McpNormalizedSubscriptionConfiguration.supporting(
								McpResourceNotificationType.RESOURCE_UPDATED))
						.build());
		McpServerCapabilityRegistry both =
				McpServerCapabilityRegistry.fromEndpoint(endpointBuilder()
						.exactResource("catalog://items/1")
						.subscriptions(McpNormalizedSubscriptionConfiguration.supporting(
								McpResourceNotificationType.RESOURCES_LIST_CHANGED,
								McpResourceNotificationType.RESOURCE_UPDATED))
						.build());

		Assertions.assertEquals(
				new McpJsonObject(Map.of("listChanged", McpJsonBoolean.TRUE)),
				listChanged.capabilities().resources().orElseThrow().toJsonObject());
		Assertions.assertEquals(
				new McpJsonObject(Map.of("subscribe", McpJsonBoolean.TRUE)),
				updated.capabilities().resources().orElseThrow().toJsonObject());
		Assertions.assertEquals(
				new McpJsonObject(Map.of(
						"listChanged", McpJsonBoolean.TRUE,
						"subscribe", McpJsonBoolean.TRUE)),
				both.capabilities().resources().orElseThrow().toJsonObject());
	}

	@Test
	public void resource_notification_configuration_cannot_invent_a_resource_surface() {
		Assertions.assertThrows(IllegalStateException.class,
				() -> endpointBuilder()
						.subscriptions(McpNormalizedSubscriptionConfiguration.supporting(
								McpResourceNotificationType.RESOURCE_UPDATED))
						.build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpNormalizedSubscriptionConfiguration(Set.of()));
	}

	@Test
	public void tool_registration_does_not_invent_a_resources_capability() {
		McpServerCapabilityRegistry toolOnly =
				McpServerCapabilityRegistry.fromEndpoint(endpointBuilder()
						.tool(McpNormalizedOperation.named("read"))
						.build());

		Assertions.assertEquals(Set.of("tools"),
				toolOnly.capabilities().toJsonObject().members().keySet());
		Assertions.assertFalse(toolOnly.capabilities().resources().isPresent());
	}

	@Test
	public void discovery_contains_required_fields_and_optional_registration_metadata() {
		McpJsonObject metadata = new McpJsonObject(Map.of(
				"com.example/revision", new McpJsonString("42")));
		McpNormalizedEndpoint endpoint = endpointBuilder()
				.instructions("Use read-only catalog tools.")
				.discoveryCachePolicy(
						new McpDiscoveryCachePolicy(30_000L, McpCacheScope.PUBLIC))
				.discoveryMetadata(metadata)
				.tool(McpNormalizedOperation.named("lookup"))
				.build();
		McpServerCapabilityRegistry registry =
				McpServerCapabilityRegistry.fromEndpoint(endpoint);
		McpWireResult result = registry.discoverResult().toWireResult();
		McpJsonRpcId.StringId requestId = new McpJsonRpcId.StringId("discover-1");
		McpJsonObject response = new McpJsonRpcMessage.ResultResponse(
				requestId, result, McpJsonObject.empty()).toJsonObject();
		McpJsonObject json = (McpJsonObject) response.members().get("result");

		Assertions.assertEquals(McpResultType.COMPLETE, result.resultType());
		Assertions.assertEquals(Set.of("jsonrpc", "id", "result"),
				response.members().keySet());
		Assertions.assertFalse(response.members().containsKey("_meta"));
		Assertions.assertEquals(new McpJsonString("complete"),
				json.members().get("resultType"));
		Assertions.assertEquals(new McpJsonArray(
						List.of(new McpJsonString(McpProtocolVersion.CURRENT))),
				json.members().get("supportedVersions"));
		Assertions.assertEquals(new McpJsonNumber(30_000L),
				json.members().get("ttlMs"));
		Assertions.assertEquals(new McpJsonString("public"),
				json.members().get("cacheScope"));
		Assertions.assertEquals(new McpJsonString("Use read-only catalog tools."),
				json.members().get("instructions"));
		McpJsonObject resultMetadata = (McpJsonObject) json.members().get("_meta");
		Assertions.assertTrue(resultMetadata.members().containsKey(
				McpResultMetadata.SERVER_INFORMATION_KEY));
		Assertions.assertEquals(new McpJsonString("42"),
				resultMetadata.members().get("com.example/revision"));
	}

	@Test
	public void server_information_can_be_disabled_without_affecting_capabilities() {
		McpNormalizedEndpoint endpoint = endpointBuilder()
				.includeServerInformation(false)
				.tool(McpNormalizedOperation.named("lookup"))
				.build();
		McpWireResult discovery =
				McpServerCapabilityRegistry.fromEndpoint(endpoint)
						.discoverResult().toWireResult();
		McpJsonObject response = new McpJsonRpcMessage.ResultResponse(
				new McpJsonRpcId.IntegerId(java.math.BigInteger.ONE), discovery,
				McpJsonObject.empty()).toJsonObject();
		McpJsonObject json = (McpJsonObject) response.members().get("result");

		Assertions.assertFalse(json.members().containsKey("_meta"));
		Assertions.assertFalse(response.members().containsKey("_meta"));
		Assertions.assertEquals(Set.of("supportedVersions", "capabilities", "resultType",
				"ttlMs", "cacheScope"), json.members().keySet());
		Assertions.assertEquals(new McpJsonNumber(0L), json.members().get("ttlMs"));
		Assertions.assertEquals(new McpJsonString("private"),
				json.members().get("cacheScope"));
		Assertions.assertTrue(
				((McpJsonObject) json.members().get("capabilities"))
						.members().containsKey("tools"));
	}

	@Test
	public void operation_capability_plans_include_tools_prompts_and_resource_reads() {
		McpInputRequestDeclaration declaration =
				McpInputRequestDeclaration.roots(McpInputRequirement.CONDITIONAL);
		McpInputRequestPlan plan = new McpInputRequestPlan(List.of(declaration));
		McpServerCapabilityRegistry registry =
				McpServerCapabilityRegistry.fromEndpoint(endpointBuilder()
						.tool(new McpNormalizedOperation("lookup", plan))
						.prompt(new McpNormalizedOperation("summarize", plan))
						.exactResource(new McpNormalizedOperation(
								"catalog://items/1", plan))
						.resourceTemplate(new McpNormalizedOperation(
								"catalog://items/{id}", plan))
						.build());

		Assertions.assertEquals(Optional.of(plan),
				registry.inputRequestPlan(McpOperationKind.TOOL, "lookup"));
		Assertions.assertEquals(Optional.of(plan),
				registry.inputRequestPlan(McpOperationKind.PROMPT, "summarize"));
		Assertions.assertEquals(Optional.of(plan),
				registry.inputRequestPlan(McpOperationKind.RESOURCE, "catalog://items/1"));
		Assertions.assertEquals(Optional.of(plan),
				registry.inputRequestPlan(McpOperationKind.RESOURCE, "catalog://items/{id}"));
		Assertions.assertTrue(
				registry.inputRequestPlan(McpOperationKind.PROMPT, "lookup").isEmpty());
	}

	@Test
	public void mirrored_header_plans_are_indexed_per_tool_with_a_cors_union() {
		McpMirroredHeaderPlan firstPlan = new McpMirroredHeaderPlan(List.of(
				new McpMirroredHeaderDeclaration("Tenant", List.of("tenant"),
						McpMirroredHeaderValueType.STRING)));
		McpMirroredHeaderPlan secondPlan = new McpMirroredHeaderPlan(List.of(
				new McpMirroredHeaderDeclaration("Region", List.of("region"),
						McpMirroredHeaderValueType.STRING),
				new McpMirroredHeaderDeclaration("tenant", List.of("account"),
						McpMirroredHeaderValueType.STRING)));
		McpServerCapabilityRegistry registry = McpServerCapabilityRegistry.fromEndpoint(
				endpointBuilder()
						.tool(new McpNormalizedOperation("first",
								McpInputRequestPlan.empty(), firstPlan))
						.tool(new McpNormalizedOperation("second",
								McpInputRequestPlan.empty(), secondPlan))
						.build());

		Assertions.assertEquals(Optional.of(firstPlan),
				registry.toolMirroredHeaderPlan("first"));
		Assertions.assertEquals(Optional.of(secondPlan),
				registry.toolMirroredHeaderPlan("second"));
		Assertions.assertTrue(registry.toolMirroredHeaderPlan("absent").isEmpty());
		Assertions.assertEquals(Set.of("Mcp-Param-Tenant", "Mcp-Param-Region"),
				registry.customMirroredHeaderNames());
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> registry.customMirroredHeaderNames().add("Mcp-Param-Mutation"));
	}

	@Test
	public void duplicate_catalog_names_fail_during_normalization() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> endpointBuilder()
						.tool(McpNormalizedOperation.named("lookup"))
						.tool(McpNormalizedOperation.named("lookup"))
						.build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> endpointBuilder()
						.exactResource("catalog://items/1")
						.exactResource("catalog://items/1")
						.build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> endpointBuilder()
						.exactResource("catalog://items/1")
						.resourceTemplate("catalog://items/1")
						.build());

		McpMirroredHeaderPlan mirroredHeaders = new McpMirroredHeaderPlan(List.of(
				new McpMirroredHeaderDeclaration("Tenant", List.of("tenant"),
						McpMirroredHeaderValueType.STRING)));
		for (java.util.function.Consumer<McpNormalizedEndpoint.Builder> registration
				: List.<java.util.function.Consumer<McpNormalizedEndpoint.Builder>>of(
					builder -> builder.prompt(new McpNormalizedOperation("prompt",
							McpInputRequestPlan.empty(), mirroredHeaders)),
					builder -> builder.exactResource(new McpNormalizedOperation(
							"catalog://item", McpInputRequestPlan.empty(), mirroredHeaders)),
					builder -> builder.resourceTemplate(new McpNormalizedOperation(
							"catalog://{item}", McpInputRequestPlan.empty(), mirroredHeaders))))
			Assertions.assertThrows(IllegalArgumentException.class, () -> {
				McpNormalizedEndpoint.Builder builder = endpointBuilder();
				registration.accept(builder);
				builder.build();
			});
	}

	@Test
	public void deprecated_request_log_level_never_advertises_logging() {
		McpRequestMetadata requestMetadata = new McpRequestMetadata(
				McpProtocolVersion.CURRENT,
				McpClientCapabilities.empty(),
				Optional.empty(),
				Optional.of(McpRequestLogLevel.DEBUG),
				Optional.empty(),
				McpJsonObject.empty());
		McpServerCapabilityRegistry registry =
				McpServerCapabilityRegistry.fromEndpoint(endpointBuilder().build());

		Assertions.assertEquals(new McpJsonString("debug"),
				requestMetadata.toJsonObject().members().get(
						McpRequestMetadata.LOG_LEVEL_KEY));
		Assertions.assertFalse(
				registry.capabilities().toJsonObject().members().containsKey("logging"));
	}

	private static McpNormalizedEndpoint.Builder endpointBuilder() {
		return McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion("test-server", "3.6.0"));
	}
}
