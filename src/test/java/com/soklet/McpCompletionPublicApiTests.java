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

import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public contracts for prompt and resource-template argument completion. */
@ThreadSafe
class McpCompletionPublicApiTests {
	@Test
	void valuesKeepExactOrderAndDefensivelyCopyWithinTheHundredValueBound() {
		List<String> mutable = new ArrayList<>(List.of(
				"python", "", "  spaced  ", "python", "東京"));
		McpArgumentCompletionResult result =
				McpArgumentCompletionResult.fromValues(mutable);
		mutable.clear();

		assertEquals(List.of("python", "", "  spaced  ", "python", "東京"),
				result.getValues());
		assertThrows(UnsupportedOperationException.class,
				() -> result.getValues().add("other"));
		assertTrue(McpArgumentCompletionResult.fromValues(List.of())
				.getValues().isEmpty());
		assertEquals(100, McpArgumentCompletionResult.fromValues(
				Collections.nCopies(100, "x")).getValues().size());
		assertThrows(IllegalArgumentException.class, () ->
				McpArgumentCompletionResult.fromValues(
						Collections.nCopies(101, "x")));
		assertThrows(NullPointerException.class,
				() -> McpArgumentCompletionResult.fromValues(null));
		assertThrows(NullPointerException.class,
				() -> McpArgumentCompletionResult.fromValues(
						Arrays.asList("ok", null)));
	}

	@Test
	void optionalFieldsRemainIndependentAndCountsValidateAtBuild() {
		List<String> values = List.of("a", "b");
		McpArgumentCompletionResult omitted =
				McpArgumentCompletionResult.fromValues(values);
		McpArgumentCompletionResult onlyTotal =
				McpArgumentCompletionResult.withValues(values).total(2L).build();
		McpArgumentCompletionResult onlyHasMore =
				McpArgumentCompletionResult.withValues(values).hasMore(true).build();
		McpArgumentCompletionResult maxSafe =
				McpArgumentCompletionResult.withValues(values)
						.hasMore(true).total(9_007_199_254_740_991L).build();

		assertTrue(omitted.getTotal().isEmpty());
		assertTrue(omitted.getHasMore().isEmpty());
		assertEquals(2L, onlyTotal.getTotal().orElseThrow());
		assertTrue(onlyTotal.getHasMore().isEmpty());
		assertTrue(onlyHasMore.getTotal().isEmpty());
		assertTrue(onlyHasMore.getHasMore().orElseThrow());
		assertTrue(maxSafe.getHasMore().orElseThrow());
		assertEquals(9_007_199_254_740_991L,
				maxSafe.getTotal().orElseThrow());
		assertEquals(Boolean.FALSE, McpArgumentCompletionResult
				.withValues(values).total(2L).hasMore(false).build()
				.getHasMore().orElseThrow());

		assertThrows(IllegalArgumentException.class, () ->
				McpArgumentCompletionResult.withValues(values).total(-1L).build());
		assertThrows(IllegalArgumentException.class, () ->
				McpArgumentCompletionResult.withValues(values).total(1L).build());
		assertThrows(IllegalArgumentException.class, () ->
				McpArgumentCompletionResult.withValues(values)
						.total(9_007_199_254_740_992L).build());
		assertThrows(IllegalArgumentException.class, () ->
				McpArgumentCompletionResult.withValues(values)
						.total(3L).hasMore(false).build());
		assertThrows(IllegalArgumentException.class, () ->
				McpArgumentCompletionResult.withValues(values)
						.hasMore(true).total(2L).build());
		assertThrows(NullPointerException.class, () ->
				McpArgumentCompletionResult.withValues(values).total(null));
		assertThrows(NullPointerException.class, () ->
				McpArgumentCompletionResult.withValues(values).hasMore(null));
	}

	@Test
	void resultMetadataEqualityAndDiagnosticsDoNotExposeSuggestions() {
		String secret = "sensitive-suggestion-token";
		McpJsonObject metadata = McpJsonObject.builder()
				.put("application", "sensitive-metadata-token").build();
		McpArgumentCompletionResult first =
				McpArgumentCompletionResult.withValues(List.of(secret))
						.total(1L).hasMore(false).metadata(metadata).build();
		McpArgumentCompletionResult equal =
				McpArgumentCompletionResult.withValues(List.of(secret))
						.total(1L).hasMore(false).metadata(metadata).build();

		assertSame(metadata, first.getMetadata());
		assertEquals(first, equal);
		assertEquals(first.hashCode(), equal.hashCode());
		assertFalse(first.equals(McpArgumentCompletionResult.fromValues(
				List.of(secret))));
		assertEquals(McpJsonObject.emptyInstance(),
				McpArgumentCompletionResult.fromValues(List.of()).getMetadata());
		assertFalse(first.toString().contains(secret));
		assertFalse(first.toString().contains("sensitive-metadata-token"));
		assertThrows(NullPointerException.class, () ->
				McpArgumentCompletionResult.withValues(List.of()).metadata(null));
		assertThrows(IllegalArgumentException.class, () ->
				McpArgumentCompletionResult.withValues(List.of())
						.metadata(McpJsonObject.builder()
								.put("io.mcp/private", "not-application-metadata")
								.build()));
	}

	@Test
	void registrationAttachmentUsesOptionalGetterAndTemplateOnlySetter() {
		McpCompletionHandler first = (requestContext, completionContext,
				invocationFeatures) -> McpArgumentCompletionResult.fromValues(
				List.of("first"));
		McpCompletionHandler replacement = (requestContext, completionContext,
				invocationFeatures) -> McpArgumentCompletionResult.fromValues(
				List.of("replacement"));
		McpPromptHandler promptHandler = (request, prompt, features) ->
				McpCompleteResult.fromPromptOutput(McpPromptOutput.fromMessages());
		McpResourceReadHandler readHandler = (request, resource, features) ->
				McpCompleteResult.fromResourceOutput(McpResourceOutput.withContent(
						McpTextResourceContents.withUriAndText(
								URI.create("catalog://item"), "text").build())
						.build());

		assertTrue(McpPromptRegistration.withName("prompt", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler(promptHandler).build().getCompletionHandler().isEmpty());
		assertSame(replacement, McpPromptRegistration.withName("prompt", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler(promptHandler)
				.completionHandler(first, java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28)).completionHandler(replacement, java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28)).build()
				.getCompletionHandler().orElseThrow());
		assertThrows(NullPointerException.class, () ->
				McpPromptRegistration.withName("prompt", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
						.handler(promptHandler).completionHandler(null, java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28)));

		assertTrue(McpResourceRegistration.withUriAndName(
				URI.create("catalog://item"), "item", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler(readHandler).build().getCompletionHandler().isEmpty());
		assertTrue(McpResourceRegistration.withUriTemplateAndName(
				"catalog://item/{id}", "item", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler(readHandler).build().getCompletionHandler().isEmpty());
		assertSame(replacement, McpResourceRegistration
				.withUriTemplateAndName("catalog://item/{id}", "item", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler(readHandler)
				.completionHandler(first, java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28)).completionHandler(replacement, java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28)).build()
				.getCompletionHandler().orElseThrow());
		assertThrows(NullPointerException.class, () ->
				McpResourceRegistration.withUriTemplateAndName(
						"catalog://item/{id}", "item", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
						.handler(readHandler).completionHandler(null, java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28)));
		assertFalse(Arrays.stream(McpResourceRegistration.ExactBuilder.class
				.getMethods()).anyMatch(method ->
				method.getName().equals("completionHandler")));
	}

	@Test
	void completionRegistrationsAcceptBothExactLegacyRevisions() {
		McpCompletionHandler completionHandler = (requestContext,
				completionContext, invocationFeatures) ->
				McpArgumentCompletionResult.fromValues(List.of());
		for (McpProtocolVersion protocolVersion : List.of(
				McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25)) {
			Set<McpProtocolVersion> protocolVersions = Set.of(protocolVersion);
			McpPromptRegistration prompt = completionPromptBuilder(protocolVersions)
					.completionHandler(completionHandler, protocolVersions).build();
			McpResourceRegistration resource = completionResourceBuilder(protocolVersions)
					.completionHandler(completionHandler, protocolVersions).build();
			McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
					McpImplementation.withNameAndVersion("test", "1").build(),
					protocolVersions).promptRegistrations(List.of(prompt))
					.resourceRegistrations(List.of(resource)).build();

			assertSame(completionHandler, endpoint.getPromptRegistrations().get(0)
					.getCompletionHandler().orElseThrow());
			assertSame(completionHandler, endpoint.getResourceRegistrations().get(0)
					.getCompletionHandler().orElseThrow());
			assertEquals(protocolVersions, prompt.getCompletionProtocolVersions());
			assertEquals(protocolVersions, resource.getCompletionProtocolVersions());
		}
	}

	@Test
	void completionRevisionSelectionIsSnapshottedAndMustRemainAnExactOwnerSubset() {
		McpCompletionHandler completionHandler = (requestContext,
				completionContext, invocationFeatures) ->
				McpArgumentCompletionResult.fromValues(List.of());
		Set<McpProtocolVersion> ownerVersions = Set.of(
				McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25,
				McpProtocolVersion.V2026_07_28);
		Set<McpProtocolVersion> selectedVersions = EnumSet.of(
				McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2026_07_28);
		McpPromptRegistration.Builder promptBuilder = completionPromptBuilder(ownerVersions)
				.completionHandler(completionHandler, selectedVersions);
		McpResourceRegistration.TemplateBuilder resourceBuilder =
				completionResourceBuilder(ownerVersions)
						.completionHandler(completionHandler, selectedVersions);
		selectedVersions.clear();
		Set<McpProtocolVersion> expectedVersions = Set.of(
				McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2026_07_28);
		McpPromptRegistration prompt = promptBuilder.build();
		McpResourceRegistration resource = resourceBuilder.build();
		assertEquals(expectedVersions, prompt.getCompletionProtocolVersions());
		assertEquals(expectedVersions, resource.getCompletionProtocolVersions());
		assertThrows(UnsupportedOperationException.class,
				() -> prompt.getCompletionProtocolVersions().clear());
		assertThrows(UnsupportedOperationException.class,
				() -> resource.getCompletionProtocolVersions().clear());
		Set<McpProtocolVersion> replacementVersions = Set.of(McpProtocolVersion.V2025_11_25);
		assertEquals(replacementVersions, promptBuilder
				.completionHandler(completionHandler, replacementVersions).build()
				.getCompletionProtocolVersions());
		assertEquals(replacementVersions, resourceBuilder
				.completionHandler(completionHandler, replacementVersions).build()
				.getCompletionProtocolVersions());
		assertEquals(expectedVersions, prompt.getCompletionProtocolVersions());
		assertEquals(expectedVersions, resource.getCompletionProtocolVersions());

		Set<McpProtocolVersion> juneOnly = Set.of(McpProtocolVersion.V2025_06_18);
		for (Set<McpProtocolVersion> invalidSelection : List.of(
				Set.<McpProtocolVersion>of(), replacementVersions)) {
			assertThrows(IllegalArgumentException.class, () ->
					completionPromptBuilder(juneOnly)
							.completionHandler(completionHandler, invalidSelection));
			assertThrows(IllegalArgumentException.class, () ->
					completionResourceBuilder(juneOnly)
							.completionHandler(completionHandler, invalidSelection));
		}
		McpPromptRegistration unsupportedPrompt = completionPromptBuilder(
				Set.of(McpProtocolVersion.V2025_03_26))
				.completionHandler(completionHandler,
						Set.of(McpProtocolVersion.V2025_03_26)).build();
		assertThrows(IllegalStateException.class, () -> McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("test", "1").build(),
				Set.of(McpProtocolVersion.V2025_03_26))
				.promptRegistrations(List.of(unsupportedPrompt)).build());
	}

	private static McpPromptRegistration.Builder completionPromptBuilder(
			Set<McpProtocolVersion> protocolVersions) {
		return McpPromptRegistration.withName("prompt", protocolVersions)
				.handler((requestContext, promptGetContext, invocationFeatures) ->
						McpCompleteResult.fromPromptOutput(McpPromptOutput.fromMessages()));
	}

	private static McpResourceRegistration.TemplateBuilder completionResourceBuilder(
			Set<McpProtocolVersion> protocolVersions) {
		return McpResourceRegistration.withUriTemplateAndName(
				"catalog://item/{id}", "item", protocolVersions)
				.handler((requestContext, resourceReadContext, invocationFeatures) ->
						McpCompleteResult.fromResourceOutput(McpResourceOutput.fromContent(
								McpTextResourceContents.withUriAndText(
										resourceReadContext.getUri(), "text").build())));
	}

	@Test
	void publicShapeKeepsTheAgreedContextAndFactoryBoundaries()
			throws NoSuchMethodException {
		assertTrue(McpCompletionContext.class.isAssignableFrom(
				McpCompletionContext.Prompt.class));
		assertTrue(McpCompletionContext.class.isAssignableFrom(
				McpCompletionContext.Resource.class));
		assertTrue(Arrays.stream(McpArgumentCompletionResult.class
				.getDeclaredConstructors()).noneMatch(constructor ->
				Modifier.isPublic(constructor.getModifiers())));
		assertTrue(Arrays.stream(McpArgumentCompletionResult.Builder.class
				.getDeclaredConstructors()).noneMatch(constructor ->
				Modifier.isPublic(constructor.getModifiers())));
		assertEquals(McpPromptRegistration.class,
				McpCompletionContext.Prompt.class
						.getMethod("getPromptRegistration").getReturnType());
		assertEquals(McpResourceRegistration.class,
				McpCompletionContext.Resource.class
						.getMethod("getResourceRegistration").getReturnType());
	}
}
