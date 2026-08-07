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

/**
 * Fixture-only package peer for the official JSON Schema conformance tool.
 *
 * <p>This class is compiled into the unpublished conformance fixture, not
 * Soklet itself. Its sole purpose is to reach Soklet's package-private,
 * Profile-1-enforcing conformance seam without making authored schemas part of
 * the public application API.</p>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public final class McpOfficialSchemaConformanceTool {
	private static final String TOOL_NAME = "json_schema_2020_12_tool";

	private McpOfficialSchemaConformanceTool() {
	}

	/**
	 * Creates the exact tool required by the pinned official scenario.
	 *
	 * @return immutable conformance-tool registration
	 */
	public static McpToolRegistration<McpJsonObject> create() {
		return McpToolRegistration.withName(TOOL_NAME)
				.conformanceInputSchema(inputSchema())
				.handler((request, call, features) ->
						McpCompleteResult.fromToolText("Schema input accepted."))
				.description("Tool with JSON Schema 2020-12 features")
				.build();
	}

	private static McpJsonObject inputSchema() {
		McpJsonObject addressDefinition = McpJsonObject.builder()
				.put("$anchor", "addressDef")
				.put("type", "object")
				.put("properties", McpJsonObject.builder()
						.put("street", stringSchema())
						.put("city", stringSchema())
						.build())
				.build();
		McpJsonObject properties = McpJsonObject.builder()
				.put("name", stringSchema())
				.put("address", McpJsonObject.builder()
						.put("$ref", "#/$defs/address")
						.build())
				.put("contactMethod", McpJsonObject.builder()
						.put("type", "string")
						.put("enum", McpJsonArray.builder()
								.add("phone")
								.add("email")
								.build())
						.build())
				.put("phone", stringSchema())
				.put("email", stringSchema())
				.build();
		McpJsonObject anyContact = McpJsonObject.builder()
				.put("anyOf", McpJsonArray.builder()
						.add(required("phone"))
						.add(required("email"))
						.build())
				.build();
		McpJsonObject condition = McpJsonObject.builder()
				.put("properties", McpJsonObject.builder()
						.put("contactMethod", McpJsonObject.builder()
								.put("const", "phone")
								.build())
						.build())
				.put("required", stringArray("contactMethod"))
				.build();

		return McpJsonObject.builder()
				.put("$schema",
						"https://json-schema.org/draft/2020-12/schema")
				.put("type", "object")
				.put("$defs", McpJsonObject.builder()
						.put("address", addressDefinition)
						.build())
				.put("properties", properties)
				.put("allOf", McpJsonArray.builder().add(anyContact).build())
				.put("if", condition)
				.put("then", required("phone"))
				.put("else", required("email"))
				.put("additionalProperties", false)
				.build();
	}

	private static McpJsonObject stringSchema() {
		return McpJsonObject.builder().put("type", "string").build();
	}

	private static McpJsonObject required(String property) {
		return McpJsonObject.builder()
				.put("required", stringArray(property))
				.build();
	}

	private static McpJsonArray stringArray(String value) {
		return McpJsonArray.builder().add(value).build();
	}
}
