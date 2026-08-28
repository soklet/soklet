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

package com.soklet.internal.mcp.schema;

import com.soklet.McpJsonArray;
import com.soklet.McpJsonBoolean;
import com.soklet.McpJsonNumber;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonString;
import com.soklet.annotation.McpToolProperty;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpRuntimeTypedSchemaBridgeTests {
	@Test
	void inputBridgePublishesSchemaAndValidatesDecodesAndEncodesRecords() {
		McpRuntimeTypedSchemaBridge<Arguments> bridge =
				McpRuntimeTypedSchemaBridge.compileToolInput(Arguments.class);
		McpJsonObject schema = bridge.getSchemaDocument();
		McpJsonObject properties = assertInstanceOf(McpJsonObject.class,
				schema.find("properties").orElseThrow());
		McpJsonArray required = assertInstanceOf(McpJsonArray.class,
				schema.find("required").orElseThrow());

		assertEquals(McpJsonString.fromValue("object"),
				schema.find("type").orElseThrow());
		assertEquals(Set.of("query", "limit", "pageSizes"),
				properties.getMembers().keySet());
		assertEquals(List.of(McpJsonString.fromValue("query"),
				McpJsonString.fromValue("pageSizes")), required.getElements());
		assertEquals(McpJsonBoolean.fromValue(false),
				schema.find("additionalProperties").orElseThrow());

		McpJsonObject input = McpJsonObject.builder()
				.put("query", " exact ")
				.put("pageSizes", McpJsonArray.fromElements(List.of(
						McpJsonNumber.fromValue(BigDecimal.valueOf(2)),
						McpJsonNumber.fromValue(BigDecimal.valueOf(5)))))
				.build();
		Arguments expected = new Arguments(" exact ", Optional.empty(),
				List.of(2, 5));

		assertTrue(bridge.isValid(input));
		assertEquals(expected, bridge.decode(input));
		assertPublicJsonEquals(input,
				assertInstanceOf(McpJsonObject.class, bridge.encode(expected)));
	}

	@Test
	void outputBridgeRoundTripsNestedRecordCollections() {
		McpRuntimeTypedSchemaBridge<Result> bridge =
				McpRuntimeTypedSchemaBridge.compileToolOutput(Result.class);
		Result result = new Result(List.of(new Item("b", 2),
				new Item("a", 1)));

		McpJsonObject encoded = assertInstanceOf(McpJsonObject.class,
				bridge.encode(result));
		assertTrue(bridge.isValid(encoded));
		assertEquals(result, bridge.decode(encoded));
		assertEquals(McpJsonString.fromValue("object"),
				bridge.getSchemaDocument().find("type").orElseThrow());
	}

	@Test
	void invalidPublicJsonFailsValidationAndDecode() {
		McpRuntimeTypedSchemaBridge<Arguments> bridge =
				McpRuntimeTypedSchemaBridge.compileToolInput(Arguments.class);
		McpJsonObject missingRequiredProperty = McpJsonObject.builder()
				.put("query", "exact")
				.build();
		McpJsonObject wrongPropertyType = McpJsonObject.builder()
				.put("query", 42)
				.put("pageSizes", McpJsonArray.fromElements(List.of()))
				.build();

		assertFalse(bridge.isValid(missingRequiredProperty));
		assertFalse(bridge.isValid(wrongPropertyType));
		IllegalArgumentException failure = assertThrows(
				IllegalArgumentException.class,
				() -> bridge.decode(missingRequiredProperty));
		assertEquals("The JSON value does not satisfy the compiled tool schema.",
				failure.getMessage());
	}

	@Test
	void unsupportedDeclaredTypeFailsSynchronously() {
		assertThrows(IllegalArgumentException.class,
				() -> McpRuntimeTypedSchemaBridge.compileToolOutput(UUID.class));
		assertThrows(IllegalArgumentException.class,
				() -> McpRuntimeTypedSchemaBridge.compileToolInput(String.class));
	}

	@Test
	void annotatedRecordComponentsRenameAndDocumentBoundProperties() {
		McpRuntimeTypedSchemaBridge<AnnotatedResult> bridge =
				McpRuntimeTypedSchemaBridge.compileToolOutput(AnnotatedResult.class);
		McpJsonObject schema = bridge.getSchemaDocument();
		McpJsonObject properties = assertInstanceOf(McpJsonObject.class,
				schema.find("properties").orElseThrow());
		McpJsonObject publishedProperty = assertInstanceOf(McpJsonObject.class,
				properties.find("publishedName").orElseThrow());

		assertEquals(McpJsonString.fromValue("Published title"),
				publishedProperty.find("title").orElseThrow());
		assertEquals(McpJsonString.fromValue("Published description"),
				publishedProperty.find("description").orElseThrow());
		assertFalse(properties.find("javaName").isPresent());

		AnnotatedResult value = new AnnotatedResult("value", Optional.empty());
		McpJsonObject encoded = assertInstanceOf(McpJsonObject.class,
				bridge.encode(value));
		assertEquals(McpJsonString.fromValue("value"),
				encoded.find("publishedName").orElseThrow());
		assertEquals(value, bridge.decode(encoded));
	}

	private static void assertPublicJsonEquals(McpJsonObject expected,
			McpJsonObject actual) {
		assertEquals(expected.getMembers().keySet(), actual.getMembers().keySet());
		for (String name : expected.getMembers().keySet()) {
			Object expectedValue = expected.getMembers().get(name);
			Object actualValue = actual.getMembers().get(name);
			if (expectedValue instanceof McpJsonObject expectedObject
					&& actualValue instanceof McpJsonObject actualObject)
				assertPublicJsonEquals(expectedObject, actualObject);
			else if (expectedValue instanceof McpJsonArray expectedArray
					&& actualValue instanceof McpJsonArray actualArray)
				assertEquals(expectedArray.getElements(), actualArray.getElements());
			else
				assertEquals(expectedValue, actualValue);
		}
	}

	private record Arguments(String query, Optional<Integer> limit,
			List<Integer> pageSizes) {
	}

	private record Result(List<Item> items) {
	}

	private record Item(String id, int score) {
	}

	private record AnnotatedResult(
			@McpToolProperty(name = "publishedName", title = "Published title",
					description = "Published description") String javaName,
			@McpToolProperty(title = " ", description = " ")
			Optional<Integer> optional) {
	}
}
