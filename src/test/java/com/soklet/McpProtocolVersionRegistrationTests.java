/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet;

import com.soklet.annotation.McpHeader;
import com.soklet.converter.TypeReference;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.soklet.McpProtocolVersion.V2025_06_18;
import static com.soklet.McpProtocolVersion.V2026_07_28;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpProtocolVersionRegistrationTests {
	@Test
	void endpointPathsAreValidatedWithoutRewritingTheDeclaredUrl() {
		for (String path : List.of("/mcp/", "/catalog//mcp", " /mcp", "/mcp ",
				"//mcp", "/catalog/../mcp", "/catalog/./mcp")) {
			IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
					() -> McpEndpoint.withPath(path, implementation(), Set.of(V2026_07_28)), path);
			assertTrue(failure.getMessage().contains("normalized ASCII raw URI path"), failure.getMessage());
		}
		for (String path : List.of("/mcp", "/catalog/mcp", "/caf%C3%A9/mcp", "/catalog%2Fmcp"))
			assertEquals(path, McpEndpoint.withPath(path, implementation(), Set.of(V2026_07_28)).build().getPath());
	}

	@Test
	void rejectedEndpointPathsIdentifyTheActualNormalizationProblem() {
		for (var example : List.of(java.util.Map.entry("/mcp/", "trailing slash"),
				java.util.Map.entry("/catalog//mcp", "empty path segments"),
				java.util.Map.entry(" /mcp", "whitespace"),
				java.util.Map.entry("/catalog/../mcp", "dot path segments"))) {
			IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
					() -> McpEndpoint.withPath(example.getKey(), implementation(), Set.of(V2026_07_28)));
			assertTrue(failure.getMessage().contains(example.getValue()), failure.getMessage());
			org.junit.jupiter.api.Assertions.assertFalse(failure.getMessage().contains("percent-encode"), failure.getMessage());
		}
	}

	@Test
	void appsAssociationErrorIdentifiesToolEndpointAndRevision() {
		McpToolRegistration<?> tool = McpToolRegistration.withName("missing-ui-tool", Set.of(V2026_07_28))
				.argumentType(EmptyArguments.class).handler((context, arguments, features) -> McpCompleteResult.fromToolText("ok"))
				.appToolMetadata(McpAppToolMetadata.withProtocolVersions(Set.of(V2026_07_28))
						.resourceUri(java.net.URI.create("ui://missing/resource")).build()).build();
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> McpEndpoint.withPath("/apps-diagnostic", implementation(), Set.of(V2026_07_28))
						.toolRegistrations(List.of(tool)).build());
		for (String expected : List.of("missing-ui-tool", "/apps-diagnostic", V2026_07_28.getWireValue()))
			assertTrue(failure.getMessage().contains(expected), failure.getMessage());
	}

	@Test
	void legacyOutputSchemaFailsAtEndpointConstructionWithActionableContext() {
		for (McpProtocolVersion legacy : List.of(V2025_06_18, McpProtocolVersion.V2025_11_25)) {
			Set<McpProtocolVersion> versions = Set.of(legacy, V2026_07_28);
			McpToolRegistration<?> tool = McpToolRegistration.withName("array-output", versions)
					.argumentAndOutputTypes(EmptyArguments.class, new TypeReference<List<String>>() {})
					.handler((requestContext, arguments, invocationFeatures) -> List.of("value")).build();
			IllegalStateException failure = assertThrows(IllegalStateException.class,
					() -> McpEndpoint.withPath("/catalog/mcp", implementation(), versions)
							.toolRegistrations(List.of(tool)).build());
			for (String context : List.of("array-output", "/catalog/mcp", legacy.getWireValue(), "object type"))
				assertTrue(failure.getMessage().contains(context), failure.getMessage());
		}
		McpToolRegistration<?> modern = McpToolRegistration.withName("array-output", Set.of(V2026_07_28))
				.argumentAndOutputTypes(EmptyArguments.class, new TypeReference<List<String>>() {})
				.handler((requestContext, arguments, invocationFeatures) -> List.of("value")).build();
		assertEquals(List.of(modern), McpEndpoint.withPath("/mcp", implementation(), Set.of(V2026_07_28))
				.toolRegistrations(List.of(modern)).build().getToolRegistrations());
	}

	@Test
	void revisionSubsetFailureNamesEndpointOperationAndOffendingRevision() {
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> McpEndpoint.withPath("/catalog/mcp", implementation(), Set.of(V2026_07_28))
						.toolRegistrations(List.of(tool(Set.of(V2025_06_18)))).build());
		for (String context : List.of("search", "/catalog/mcp", "2025-06-18"))
			assertTrue(failure.getMessage().contains(context), failure.getMessage());
	}

	private record EmptyArguments() {}

	@Test
	void subscriptionSurfaceFailureIdentifiesTheRevisionWhoseCatalogHasNoResources() {
		McpEndpoint endpoint = McpEndpoint.withPath("/catalog/mcp", implementation(), Set.of(V2025_06_18, V2026_07_28))
				.resourceRegistrations(List.of(legacyResourceBuilder().build()))
				.subscriptionProtocolVersions(Set.of(V2026_07_28))
				.subscriptionConfig(McpSubscriptionConfig.withEventPublisherAndNotificationTypes(
						McpSubscriptionEventPublisher.fromInMemoryDefaults(), Set.of(McpSubscriptionNotificationType.RESOURCE_UPDATED)).build()).build();
		IllegalStateException failure = assertThrows(IllegalStateException.class, () -> McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.subscriptionAuthorizer(McpSubscriptionAuthorizer.denyAllInstance()).build());
		for (String context : List.of("/catalog/mcp", "2026-07-28", "requires an exact resource"))
			assertTrue(failure.getMessage().contains(context), failure.getMessage());
	}

	@Test
	void exactWireVersionsDoNotFallback() {
		assertEquals("2025-06-18", V2025_06_18.getWireValue());
		assertEquals(V2026_07_28,
				McpProtocolVersion.fromWireValue("2026-07-28").orElseThrow());
		assertTrue(McpProtocolVersion.fromWireValue("latest").isEmpty());
	}

	@Test
	void startingFactoriesRequireImmutableNonemptyVersionSets() {
		assertThrows(IllegalArgumentException.class,
				() -> McpToolRegistration.withName("search", Set.of()));
		assertThrows(IllegalArgumentException.class,
				() -> McpEndpoint.withPath("/mcp", implementation(), Set.of()));
		assertThrows(IllegalArgumentException.class,
				() -> McpAppToolMetadata.withProtocolVersions(Set.of()));

		Set<McpProtocolVersion> mutable = new LinkedHashSet<>(List.of(
				V2025_06_18, V2026_07_28));
		McpToolRegistration<?> tool = tool(mutable);
		mutable.clear();
		assertEquals(Set.of(V2025_06_18, V2026_07_28),
				tool.getProtocolVersions());
		assertThrows(UnsupportedOperationException.class,
				() -> tool.getProtocolVersions().clear());
	}

	@Test
	void endpointChecksRevisionSubsetsAndUnimplementedFeatures() {
		McpToolRegistration<?> legacyTool = tool(Set.of(V2025_06_18));
		assertThrows(IllegalStateException.class,
				() -> McpEndpoint.withPath("/mcp", implementation(), Set.of(V2026_07_28))
						.toolRegistrations(List.of(legacyTool)).build());
		McpEndpoint dualEra = McpEndpoint.withPath("/mcp", implementation(),
				Set.of(V2025_06_18, V2026_07_28))
				.toolRegistrations(List.of(legacyTool)).build();
		assertEquals(Set.of(V2025_06_18, V2026_07_28),
				dualEra.getProtocolVersions());
		assertThrows(IllegalStateException.class,
				() -> McpEndpoint.withPath("/mcp", implementation(),
						Set.of(V2025_06_18, V2026_07_28))
						.subscriptionProtocolVersions(Set.of(V2025_06_18)).build());
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(URI.create("test://catalog/item"), "item",
						Set.of(V2025_06_18))
				.handler((requestContext, resourceReadContext,
						invocationFeatures) -> McpCompleteResult.fromResourceOutput(
								McpResourceOutput.withContent(McpTextResourceContents
										.withUriAndText(resourceReadContext.getUri(), "item")
										.build()).build()))
				.build();
		assertEquals(List.of(resource), McpEndpoint.withPath("/mcp", implementation(),
				Set.of(V2025_06_18, V2026_07_28))
				.resourceRegistrations(List.of(resource)).build().getResourceRegistrations());
	}

	@Test
	void skillRegistrationsGroupsAndListHandlersRejectLegacyRevisions() {
		McpSkillBundle bundle = McpSkillBundle.fromFiles(Map.of("SKILL.md",
				"---\nname: guide\ndescription: Guide\n---\nInstructions.\n"
						.getBytes(StandardCharsets.UTF_8)));
		for (McpProtocolVersion legacyVersion : List.of(V2025_06_18, McpProtocolVersion.V2025_11_25)) {
			Set<McpProtocolVersion> endpointVersions = Set.of(legacyVersion, V2026_07_28);
			for (Set<McpProtocolVersion> skillVersions : List.of(Set.of(legacyVersion), endpointVersions)) {
				McpSkillRegistration skill = McpSkillRegistration.withUriAndSkillBundle(
						URI.create("skill://versions/guide/SKILL.md"), bundle, skillVersions).build();
				assertThrows(IllegalStateException.class, () -> McpEndpoint.withPath("/mcp", implementation(),
						endpointVersions).skillRegistrations(List.of(skill)).build());
				assertThrows(IllegalStateException.class, () -> McpEndpoint.withPath("/mcp", implementation(),
						endpointVersions).skillGroups(List.of(McpSkillGroup.fromKeyAndSkillRegistrations(
								"guide", List.of(skill)))).build());
				assertThrows(IllegalStateException.class, () -> McpEndpoint.withPath("/mcp", implementation(),
						endpointVersions).skillListHandler((requestContext, skillListContext, invocationFeatures) ->
								McpSkillPage.builder().build(), skillVersions).build());
			}
		}
	}

	@Test
	void appOnlyToolCannotLeakIntoToolsOnlyLegacyRevision() {
		McpAppToolMetadata apps = McpAppToolMetadata
				.withProtocolVersions(Set.of(V2026_07_28))
				.visibility(Set.of(McpAppToolMetadata.Visibility.APP)).build();
		McpToolRegistration<?> tool = McpToolRegistration
				.withName("helper", Set.of(V2025_06_18, V2026_07_28))
				.jsonObjectArguments()
				.handler((requestContext, arguments, invocationFeatures) ->
						McpCompleteResult.fromToolText("ok"))
				.appToolMetadata(apps).build();
		assertThrows(IllegalStateException.class,
				() -> McpEndpoint.withPath("/mcp", implementation(),
						Set.of(V2025_06_18, V2026_07_28))
						.toolRegistrations(List.of(tool)).build());
	}

	@Test
	void legacyToolRejectsModernOnlyInputAndStateFacilitiesAtConstruction() {
		McpToolRegistration<?> inputRequestTool = McpToolRegistration
				.withName("input-request", Set.of(V2025_06_18))
				.jsonObjectArguments()
				.handler((requestContext, arguments, invocationFeatures) ->
						McpCompleteResult.fromToolText("ok"))
				.inputRequestDeclarations(List.of(
						McpInputRequestDeclaration.fromElicitationUrl(
								McpInputRequirement.CONDITIONAL)))
				.build();
		McpToolRegistration<?> requestStateTool = McpToolRegistration
				.withName("request-state", Set.of(V2025_06_18))
				.jsonObjectArguments()
				.handler((requestContext, arguments, invocationFeatures) ->
						McpCompleteResult.fromToolText("ok"))
				.requestStateMode(McpRequestStateMode.APPLICATION_PROTECTED)
				.build();
		McpToolRegistration<?> mirroredHeaderTool = McpToolRegistration
				.withName("mirrored-header", Set.of(V2025_06_18))
				.argumentType(MirroredArguments.class)
				.handler((requestContext, arguments, invocationFeatures) ->
						McpCompleteResult.fromToolText("ok"))
				.build();
		for (McpToolRegistration<?> tool : List.of(inputRequestTool,
				requestStateTool, mirroredHeaderTool))
			assertThrows(IllegalStateException.class,
					() -> McpEndpoint.withPath("/mcp", implementation(),
							Set.of(V2025_06_18))
							.toolRegistrations(List.of(tool)).build());
	}

	private record MirroredArguments(@McpHeader(name = "Tenant") String tenant) {
	}

	@Test
	void legacyPromptsPermitOrdinaryOutputButRejectModernOnlyFacilities() {
		McpPromptRegistration ordinary = legacyPromptBuilder().build();
		assertEquals(List.of(ordinary), McpEndpoint.withPath("/mcp", implementation(), Set.of(V2025_06_18))
				.promptRegistrations(List.of(ordinary)).build().getPromptRegistrations());
		McpPromptRegistration inputRequest = legacyPromptBuilder()
				.inputRequestDeclarations(List.of(McpInputRequestDeclaration.fromElicitationUrl(
						McpInputRequirement.CONDITIONAL))).build();
		McpPromptRegistration requestState = legacyPromptBuilder()
				.requestStateMode(McpRequestStateMode.APPLICATION_PROTECTED).build();
		for (McpPromptRegistration prompt : List.of(inputRequest, requestState))
			assertThrows(IllegalStateException.class, () -> McpEndpoint.withPath("/mcp", implementation(),
					Set.of(V2025_06_18)).promptRegistrations(List.of(prompt)).build());
	}

	@Test
	void legacyResourcesRejectInputStateAndAppsAtConstruction() {
		List<McpResourceRegistration> resources = List.of(
				legacyResourceBuilder().inputRequestDeclarations(List.of(
						McpInputRequestDeclaration.fromElicitationUrl(McpInputRequirement.CONDITIONAL))).build(),
				legacyResourceBuilder().requestStateMode(McpRequestStateMode.APPLICATION_PROTECTED).build(),
				legacyResourceBuilder().mimeType("text/html;profile=mcp-app").build(),
				legacyResourceBuilder().metadata(McpJsonObject.builder()
						.put("ui", McpJsonObject.emptyInstance()).build()).build());
		for (McpResourceRegistration resource : resources)
			assertThrows(IllegalStateException.class, () -> McpEndpoint.withPath("/mcp", implementation(),
					Set.of(V2025_06_18)).resourceRegistrations(List.of(resource)).build());
	}

	private static McpResourceRegistration.ExactBuilder legacyResourceBuilder() {
		return McpResourceRegistration.withUriAndName(URI.create("test://ordinary"), "ordinary", Set.of(V2025_06_18))
				.handler((requestContext, resourceReadContext, invocationFeatures) ->
						McpCompleteResult.fromResourceOutput(McpResourceOutput.fromContent(McpTextResourceContents
								.withUriAndText(resourceReadContext.getUri(), "value").build())));
	}

	private static McpPromptRegistration.Builder legacyPromptBuilder() {
		return McpPromptRegistration.withName("ordinary", Set.of(V2025_06_18))
				.handler((requestContext, promptGetContext, invocationFeatures) ->
						McpCompleteResult.fromPromptOutput(McpPromptOutput.fromMessages()));
	}

	private static McpToolRegistration<?> tool(
			Set<McpProtocolVersion> protocolVersions) {
		return McpToolRegistration.withName("search", protocolVersions)
				.jsonObjectArguments()
				.handler((requestContext, arguments, invocationFeatures) ->
						McpCompleteResult.fromToolText("ok"))
				.build();
	}

	private static McpImplementation implementation() {
		return McpImplementation.withNameAndVersion("test", "1").build();
	}
}
