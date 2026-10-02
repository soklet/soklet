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
