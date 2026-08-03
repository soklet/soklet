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

import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonBoolean;
import com.soklet.internal.mcp.protocol.McpJsonNull;
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Exact Draft 2020-12 JSON instance types.
 */
enum McpSchemaType {
	NULL("null"),
	BOOLEAN("boolean"),
	OBJECT("object"),
	ARRAY("array"),
	NUMBER("number"),
	STRING("string"),
	INTEGER("integer");

	private final String schemaName;

	McpSchemaType(String schemaName) {
		this.schemaName = schemaName;
	}

	static Optional<McpSchemaType> fromSchemaName(String name) {
		requireNonNull(name);
		for (McpSchemaType type : values()) {
			if (type.schemaName.equals(name))
				return Optional.of(type);
		}
		return Optional.empty();
	}

	boolean matches(McpJsonValue instance) {
		requireNonNull(instance);
		return switch (this) {
			case NULL -> instance instanceof McpJsonNull;
			case BOOLEAN -> instance instanceof McpJsonBoolean;
			case OBJECT -> instance instanceof McpJsonObject;
			case ARRAY -> instance instanceof McpJsonArray;
			case NUMBER -> instance instanceof McpJsonNumber;
			case STRING -> instance instanceof McpJsonString;
			case INTEGER -> instance instanceof McpJsonNumber number
					&& (number.value().scale() <= 0
					|| number.value().stripTrailingZeros().scale() <= 0);
		};
	}
}
