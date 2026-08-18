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
import com.soklet.McpJsonNull;
import com.soklet.McpJsonNumber;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonString;
import com.soklet.McpJsonValue;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the bounded authored-schema bridge used by the conformance fixture.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpRuntimeToolInputSchemaBridgeTests {
	@Test
	void exactOfficialSchemaIsPreservedAndEnforcedThroughTheRuntimeBridge()
			throws IOException {
		McpToolSchemaProfileTestManifest.OfficialScenario scenario =
				McpToolSchemaProfileTestManifest.load().officialScenario();
		com.soklet.internal.mcp.protocol.McpJsonObject tool = object(
				McpToolSchemaProfileTestManifest.readJson(
						McpToolSchemaProfileTestManifest.PROFILE_ROOT
								+ scenario.fixture()));
		McpJsonObject inputSchema = (McpJsonObject) toPublic(
				tool.members().get("inputSchema"));
		McpRuntimeToolInputSchemaBridge bridge =
				McpRuntimeToolInputSchemaBridge.compileToolInput(inputSchema);

		assertSame(inputSchema, bridge.getSchemaDocument());
		assertTrue(bridge.getMirroredHeaderPlan().declarations().isEmpty());

		com.soklet.internal.mcp.protocol.McpJsonObject cases = object(
				McpToolSchemaProfileTestManifest.readJson(
						McpToolSchemaProfileTestManifest.PROFILE_ROOT
								+ scenario.cases()));
		int validCount = assertCases(bridge,
				array(cases.members().get("valid")), true);
		int invalidCount = assertCases(bridge,
				array(cases.members().get("invalid")), false);
		assertEquals(scenario.expectedValidCaseCount(), validCount);
		assertEquals(scenario.expectedInvalidCaseCount(), invalidCount);
	}

	@Test
	void productionJsonAndProfileCompilationLimitsRemainOnTheSeam() {
		McpJsonValue nestedData = McpJsonNull.INSTANCE;
		for (int index = 0; index < 128; ++index)
			nestedData = McpJsonArray.fromElements(List.of(nestedData));
		McpJsonObject jsonTooDeep = McpJsonObject.builder()
				.put("type", "object")
				.put("default", nestedData)
				.build();
		assertThrows(IllegalArgumentException.class,
				() -> McpRuntimeToolInputSchemaBridge
						.compileToolInput(jsonTooDeep));

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
		assertThrows(IllegalArgumentException.class,
				() -> McpRuntimeToolInputSchemaBridge
						.compileToolInput(schemaTooDeep));
	}

	private static int assertCases(McpRuntimeToolInputSchemaBridge bridge,
			com.soklet.internal.mcp.protocol.McpJsonArray cases,
			boolean expectedValid) {
		int count = 0;
		for (com.soklet.internal.mcp.protocol.McpJsonValue value : cases.values()) {
			com.soklet.internal.mcp.protocol.McpJsonObject testCase =
					object(value);
			McpJsonObject instance = (McpJsonObject) toPublic(
					testCase.members().get("instance"));
			if (expectedValid)
				assertSame(instance, bridge.decode(instance));
			else
				assertThrows(IllegalArgumentException.class,
						() -> bridge.decode(instance));
			count++;
		}
		return count;
	}

	private static com.soklet.internal.mcp.protocol.McpJsonObject object(
			com.soklet.internal.mcp.protocol.McpJsonValue value) {
		return (com.soklet.internal.mcp.protocol.McpJsonObject) value;
	}

	private static com.soklet.internal.mcp.protocol.McpJsonArray array(
			com.soklet.internal.mcp.protocol.McpJsonValue value) {
		return (com.soklet.internal.mcp.protocol.McpJsonArray) value;
	}

	private static McpJsonValue toPublic(
			com.soklet.internal.mcp.protocol.McpJsonValue value) {
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonString string)
			return McpJsonString.fromValue(string.value());
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonNumber number)
			return McpJsonNumber.fromValue(number.value());
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonBoolean bool)
			return McpJsonBoolean.fromValue(
					bool == com.soklet.internal.mcp.protocol.McpJsonBoolean.TRUE);
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonNull)
			return McpJsonNull.INSTANCE;
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonArray array) {
			List<McpJsonValue> elements = new ArrayList<>(array.values().size());
			array.values().forEach(element -> elements.add(toPublic(element)));
			return McpJsonArray.fromElements(elements);
		}
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonObject object) {
			Map<String, McpJsonValue> members =
					new LinkedHashMap<>(object.members().size());
			object.members().forEach((name, member) ->
					members.put(name, toPublic(member)));
			return McpJsonObject.fromMembers(members);
		}
		throw new IllegalArgumentException("Unsupported internal MCP JSON value.");
	}
}
