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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Focused closed-profile schema localization traversal tests. */
@ThreadSafe
class McpLocalizationSchemaWalkerTests {
	@Test
	void walksOnlySupportedSchemaLocationsInDeterministicOrder() {
		McpJsonObject schema = McpJsonObject.builder()
				.put("description", "Root description")
				.put("title", "Root title")
				.put("properties", McpJsonObject.builder()
						.put("z/name", McpJsonObject.builder()
								.put("title", "Z title")
								.build())
						.put("a~name", McpJsonObject.builder()
								.put("description", "A description")
								.build())
						.build())
				.put("items", McpJsonObject.builder()
						.put("title", "Item title")
						.build())
				.put("allOf", McpJsonArray.builder()
						.add(McpJsonObject.builder()
								.put("description", "All description")
								.build())
						.build())
				.put("default", McpJsonObject.builder()
						.put("title", "Default data")
						.build())
				.put("const", McpJsonObject.builder()
						.put("description", "Const data")
						.build())
				.put("examples", McpJsonArray.builder()
						.add(McpJsonObject.builder()
								.put("title", "Example data")
								.build())
						.build())
				.build();

		assertEquals(List.of(
				new McpLocalizationSchemaWalker.SchemaText(
						"/title", "Root title"),
				new McpLocalizationSchemaWalker.SchemaText(
						"/description", "Root description"),
				new McpLocalizationSchemaWalker.SchemaText(
						"/properties/a~0name/description", "A description"),
				new McpLocalizationSchemaWalker.SchemaText(
						"/properties/z~1name/title", "Z title"),
				new McpLocalizationSchemaWalker.SchemaText(
						"/items/title", "Item title"),
				new McpLocalizationSchemaWalker.SchemaText(
						"/allOf/0/description", "All description")),
				McpLocalizationSchemaWalker.walk(schema));
	}

	@Test
	void skipsBooleanSchemasAndBlankAnnotations() {
		McpJsonObject schema = McpJsonObject.builder()
				.put("title", "  ")
				.put("properties", McpJsonObject.builder()
						.put("allowed", McpJsonBoolean.fromValue(true))
						.build())
				.build();

		assertEquals(List.of(), McpLocalizationSchemaWalker.walk(schema));
	}
}
