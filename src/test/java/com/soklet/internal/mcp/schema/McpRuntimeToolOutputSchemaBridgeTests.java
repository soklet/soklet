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
import com.soklet.McpJsonNull;
import com.soklet.McpJsonNumber;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonString;
import com.soklet.McpJsonValue;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for production compilation and evaluation of persisted tool-output
 * schemas.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpRuntimeToolOutputSchemaBridgeTests {
	@Test
	void exactPersistedSchemaIsPreservedAndEnforced() {
		McpJsonObject schema = McpJsonObject.builder()
				.put("$schema",
						"https://json-schema.org/draft/2020-12/schema")
				.put("type", "object")
				.put("properties", McpJsonObject.builder()
						.put("score", McpJsonObject.builder()
								.put("type", "integer")
								.put("minimum", 0)
								.build())
						.build())
				.put("required", McpJsonArray.fromElements(List.of(
						McpJsonString.fromValue("score"))))
				.put("additionalProperties", false)
				.build();
		McpRuntimeToolOutputSchemaBridge bridge =
				McpRuntimeToolOutputSchemaBridge.compileToolOutput(schema);

		assertSame(schema, bridge.getSchemaDocument());
		assertTrue(bridge.isValid(McpJsonObject.builder()
				.put("score", 7)
				.build()));
		assertFalse(bridge.isValid(McpJsonObject.builder()
				.put("score", -1)
				.build()));
		assertFalse(bridge.isValid(McpJsonObject.builder()
				.put("score", 7)
				.put("unexpected", true)
				.build()));
	}

	@Test
	void toolOutputUsePermitsACollectionRoot() {
		McpRuntimeToolOutputSchemaBridge bridge =
				McpRuntimeToolOutputSchemaBridge.compileToolOutput(
						McpJsonObject.builder()
								.put("type", "array")
								.put("items", McpJsonObject.builder()
										.put("type", "integer")
										.build())
								.build());

		assertTrue(bridge.isValid(McpJsonArray.fromElements(List.of(
				McpJsonNumber.fromValue(BigDecimal.ONE),
				McpJsonNumber.fromValue(BigDecimal.TEN)))));
		assertFalse(bridge.isValid(McpJsonObject.builder().build()));
	}

	@Test
	void toolInputOnlyHeaderDeclarationsAreRejected() {
		McpJsonObject outputSchema = McpJsonObject.builder()
				.put("type", "object")
				.put("properties", McpJsonObject.builder()
						.put("region", McpJsonObject.builder()
								.put("type", "string")
								.put("x-mcp-header", "Region")
								.build())
						.build())
				.build();

		assertThrows(IllegalArgumentException.class, () ->
				McpRuntimeToolOutputSchemaBridge.compileToolOutput(outputSchema));
	}

	@Test
	void productionJsonAndProfileLimitsRemainOnCompilationAndEvaluation() {
		McpJsonValue nestedData = McpJsonNull.INSTANCE;
		for (int index = 0; index < 128; ++index)
			nestedData = McpJsonArray.fromElements(List.of(nestedData));
		McpJsonObject jsonTooDeep = McpJsonObject.builder()
				.put("type", "object")
				.put("default", nestedData)
				.build();
		assertThrows(IllegalArgumentException.class, () ->
				McpRuntimeToolOutputSchemaBridge.compileToolOutput(jsonTooDeep));

		McpJsonObject nestedSchema = McpJsonObject.builder()
				.put("type", "string")
				.build();
		for (int index = 0; index < 64; ++index)
			nestedSchema = McpJsonObject.builder()
					.put("type", "object")
					.put("properties", McpJsonObject.builder()
							.put("child", nestedSchema)
							.build())
					.build();
		McpJsonObject schemaTooDeep = nestedSchema;
		assertThrows(IllegalArgumentException.class, () ->
				McpRuntimeToolOutputSchemaBridge.compileToolOutput(schemaTooDeep));

		McpRuntimeToolOutputSchemaBridge bridge =
				McpRuntimeToolOutputSchemaBridge.compileToolOutput(
						McpJsonObject.builder().build());
		McpJsonValue outputTooDeep = McpJsonNull.INSTANCE;
		for (int index = 0; index < 128; ++index)
			outputTooDeep = McpJsonArray.fromElements(List.of(outputTooDeep));
		assertFalse(bridge.isValid(outputTooDeep));
	}

	@Test
	@SuppressWarnings("DataFlowIssue")
	void nullContractsAreExplicit() {
		assertThrows(NullPointerException.class, () ->
				McpRuntimeToolOutputSchemaBridge.compileToolOutput(null));
		McpRuntimeToolOutputSchemaBridge bridge =
				McpRuntimeToolOutputSchemaBridge.compileToolOutput(
						McpJsonObject.builder().build());
		assertThrows(NullPointerException.class, () -> bridge.isValid(null));
	}
}
