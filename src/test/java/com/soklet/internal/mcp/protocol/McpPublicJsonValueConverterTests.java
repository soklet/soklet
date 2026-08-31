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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class McpPublicJsonValueConverterTests {
	@Test
	public void conversionPreservesEveryPublicJsonVariant() {
		Map<String, com.soklet.McpJsonValue> publicMembers =
				new LinkedHashMap<>();
		publicMembers.put("string", com.soklet.McpJsonString.fromValue("value"));
		publicMembers.put("number",
				com.soklet.McpJsonNumber.fromValue(new BigDecimal("12.50")));
		publicMembers.put("true", com.soklet.McpJsonBoolean.fromValue(true));
		publicMembers.put("false", com.soklet.McpJsonBoolean.fromValue(false));
		publicMembers.put("null", com.soklet.McpJsonNull.INSTANCE);
		publicMembers.put("array", com.soklet.McpJsonArray.fromElements(List.of(
				com.soklet.McpJsonString.fromValue("nested"))));
		com.soklet.McpJsonObject publicObject =
				com.soklet.McpJsonObject.fromMembers(publicMembers);

		McpJsonObject converted =
				McpPublicJsonValueConverter.toInternalObject(publicObject);

		Map<String, McpJsonValue> expectedMembers = new LinkedHashMap<>();
		expectedMembers.put("string", new McpJsonString("value"));
		expectedMembers.put("number", new McpJsonNumber(
				new BigDecimal("12.50")));
		expectedMembers.put("true", McpJsonBoolean.TRUE);
		expectedMembers.put("false", McpJsonBoolean.FALSE);
		expectedMembers.put("null", McpJsonNull.INSTANCE);
		expectedMembers.put("array", new McpJsonArray(List.of(
				new McpJsonString("nested"))));
		Assertions.assertEquals(new McpJsonObject(expectedMembers), converted);
	}

	@Test
	public void preflightAcceptsDepthBoundaryAndRejectsOneOver() {
		McpJsonLimits limits = limits(3, 16, 128, 128, 32, 32, 256);

		Assertions.assertDoesNotThrow(() ->
				McpPublicJsonValueConverter.toInternal(nestedValue(3), limits));
		IllegalArgumentException exception = Assertions.assertThrows(
				IllegalArgumentException.class, () ->
						McpPublicJsonValueConverter.toInternal(
								nestedValue(4), limits));
		Assertions.assertEquals(
				"JSON output exceeds the configured depth limit.",
				exception.getMessage());
	}

	@Test
	public void preflightAcceptsNodeBoundaryAndRejectsOneOver() {
		McpJsonLimits limits = limits(8, 4, 128, 128, 32, 32, 256);

		Assertions.assertDoesNotThrow(() ->
				McpPublicJsonValueConverter.toInternal(publicArray(3), limits));
		IllegalArgumentException exception = Assertions.assertThrows(
				IllegalArgumentException.class, () ->
						McpPublicJsonValueConverter.toInternal(
								publicArray(4), limits));
		Assertions.assertEquals(
				"JSON output exceeds the configured node limit.",
				exception.getMessage());
	}

	@Test
	public void productionNodeCountUsesCodecStructuralAccounting() {
		com.soklet.McpJsonObject value = com.soklet.McpJsonObject.fromMembers(
				Map.of("values", com.soklet.McpJsonArray.fromElements(List.of(
						com.soklet.McpJsonNull.INSTANCE,
						com.soklet.McpJsonString.fromValue("value")))));

		Assertions.assertEquals(4,
				McpPublicJsonValueConverter.productionNodeCount(value));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpPublicJsonValueConverter.productionNodeCount(nestedValue(129)));
		int maximumNodes = McpJsonLimits.productionDefaults().maximumNodeCount();
		Assertions.assertDoesNotThrow(() ->
				McpPublicJsonValueConverter.requireProductionNodeCount(
						maximumNodes, "Test aggregate"));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpPublicJsonValueConverter.requireProductionNodeCount(
						(long) maximumNodes + 1L, "Test aggregate"));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpPublicJsonValueConverter.requireProductionNodeCount(
						-1L, "Test aggregate"));
	}

	@Test
	public void exactCodecValidationCoversStringsUnicodeNumbersAndMemberNames() {
		McpJsonLimits limits = limits(8, 16, 4, 4, 4, 3, 128);

		Assertions.assertDoesNotThrow(() ->
				McpPublicJsonValueConverter.toInternal(
						com.soklet.McpJsonString.fromValue("abcd"), limits));
		Assertions.assertDoesNotThrow(() ->
				McpPublicJsonValueConverter.toInternal(
						com.soklet.McpJsonNumber.fromValue(
								new BigDecimal("1E+3")), limits));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpPublicJsonValueConverter.toInternal(
						com.soklet.McpJsonString.fromValue("abcde"), limits));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpPublicJsonValueConverter.toInternal(
						com.soklet.McpJsonString.fromValue("bad\uD800"), limits));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpPublicJsonValueConverter.toInternal(
						com.soklet.McpJsonNumber.fromValue(
								new BigDecimal("1E+4")), limits));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpPublicJsonValueConverter.toInternalObject(
						com.soklet.McpJsonObject.fromMembers(Map.of(
								"abcde", com.soklet.McpJsonNull.INSTANCE)), limits));
	}

	@Test
	public void exactCodecValidationAcceptsOutputByteBoundaryAndRejectsOneOver() {
		McpJsonLimits limits = limits(8, 16, 16, 16, 16, 16, 5);

		Assertions.assertDoesNotThrow(() ->
				McpPublicJsonValueConverter.toInternal(
						com.soklet.McpJsonString.fromValue("abc"), limits));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpPublicJsonValueConverter.toInternal(
						com.soklet.McpJsonString.fromValue("abcd"), limits));
	}

	@Test
	public void collectionLowerBoundAcceptsBoundaryAndRejectsOneOverWithoutOverflow() {
		McpJsonLimits limits = limits(8, 10, 16, 16, 16, 16, 128);

		Assertions.assertDoesNotThrow(() ->
				McpPublicJsonValueConverter.requireCollectionCouldFitNodeBudget(
						4L, 2L, 2L, "Test collection", limits));
		IllegalArgumentException oneOver = Assertions.assertThrows(
				IllegalArgumentException.class, () ->
						McpPublicJsonValueConverter.requireCollectionCouldFitNodeBudget(
								5L, 2L, 2L, "Test collection", limits));
		Assertions.assertEquals(
				"Test collection cannot fit within the configured JSON node limit.",
				oneOver.getMessage());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpPublicJsonValueConverter.requireCollectionCouldFitNodeBudget(
						Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
						"Test collection", limits));
	}

	@Test
	public void conversionRejectsNullInputsAndLimits() {
		Assertions.assertThrows(NullPointerException.class, () ->
				McpPublicJsonValueConverter.toInternal(null));
		Assertions.assertThrows(NullPointerException.class, () ->
				McpPublicJsonValueConverter.toInternalObject(null));
		Assertions.assertThrows(NullPointerException.class, () ->
				McpPublicJsonValueConverter.toInternal(
						com.soklet.McpJsonNull.INSTANCE, null));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpPublicJsonValueConverter.requireCollectionCouldFitNodeBudget(
						-1L, 1L, 0L, "Test collection",
						McpJsonLimits.productionDefaults()));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpPublicJsonValueConverter.requireCollectionCouldFitNodeBudget(
						0L, 0L, 0L, "Test collection",
						McpJsonLimits.productionDefaults()));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpPublicJsonValueConverter.requireCollectionCouldFitNodeBudget(
						0L, 1L, -1L, "Test collection",
						McpJsonLimits.productionDefaults()));
	}

	private static com.soklet.McpJsonValue nestedValue(int depth) {
		com.soklet.McpJsonValue value = com.soklet.McpJsonNull.INSTANCE;
		for (int index = 1; index < depth; index++)
			value = com.soklet.McpJsonArray.fromElements(List.of(value));
		return value;
	}

	private static com.soklet.McpJsonArray publicArray(int elementCount) {
		com.soklet.McpJsonArray.Builder builder = com.soklet.McpJsonArray.builder();
		for (int index = 0; index < elementCount; index++)
			builder.addNull();
		return builder.build();
	}

	private static McpJsonLimits limits(int maximumDepth,
			int maximumNodes, int maximumTokenCharacters,
			int maximumStringCharacters, int maximumNumberCharacters,
			int maximumExponentMagnitude, int maximumOutputBytes) {
		return new McpJsonLimits(1_024, maximumDepth,
				maximumTokenCharacters, maximumStringCharacters,
				maximumNumberCharacters, maximumExponentMagnitude,
				maximumNodes, maximumOutputBytes);
	}
}
