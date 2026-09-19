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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the first, non-routable Completion public API slice. */
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

		assertTrue(McpPromptRegistration.withName("prompt")
				.handler(promptHandler).build().getCompletionHandler().isEmpty());
		assertSame(replacement, McpPromptRegistration.withName("prompt")
				.handler(promptHandler)
				.completionHandler(first).completionHandler(replacement).build()
				.getCompletionHandler().orElseThrow());
		assertThrows(NullPointerException.class, () ->
				McpPromptRegistration.withName("prompt")
						.handler(promptHandler).completionHandler(null));

		assertTrue(McpResourceRegistration.withUriAndName(
				URI.create("catalog://item"), "item")
				.handler(readHandler).build().getCompletionHandler().isEmpty());
		assertTrue(McpResourceRegistration.withUriTemplateAndName(
				"catalog://item/{id}", "item")
				.handler(readHandler).build().getCompletionHandler().isEmpty());
		assertSame(replacement, McpResourceRegistration
				.withUriTemplateAndName("catalog://item/{id}", "item")
				.handler(readHandler)
				.completionHandler(first).completionHandler(replacement).build()
				.getCompletionHandler().orElseThrow());
		assertThrows(NullPointerException.class, () ->
				McpResourceRegistration.withUriTemplateAndName(
						"catalog://item/{id}", "item")
						.handler(readHandler).completionHandler(null));
		assertFalse(Arrays.stream(McpResourceRegistration.ExactBuilder.class
				.getMethods()).anyMatch(method ->
				method.getName().equals("completionHandler")));
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
