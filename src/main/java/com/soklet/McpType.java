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

import org.jspecify.annotations.NonNull;

/**
 * Supported scalar schema types for the programmatic MCP schema DSL.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum McpType {
	STRING,
	INTEGER,
	NUMBER,
	BOOLEAN,
	UUID;

	@NonNull
	McpObject toSchemaValue() {
		return switch (this) {
			case STRING -> new McpObject(java.util.Map.of("type", new McpString("string")));
			case INTEGER -> new McpObject(java.util.Map.of("type", new McpString("integer")));
			case NUMBER -> new McpObject(java.util.Map.of("type", new McpString("number")));
			case BOOLEAN -> new McpObject(java.util.Map.of("type", new McpString("boolean")));
			case UUID -> new McpObject(java.util.Map.of(
					"type", new McpString("string"),
					"format", new McpString("uuid")
			));
		};
	}
}
