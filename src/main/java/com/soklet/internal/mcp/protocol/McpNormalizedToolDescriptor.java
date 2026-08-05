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

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable tool catalog projection derived from the same registration plan
 * that owns execution.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpNormalizedToolDescriptor(@NonNull String name,
		@NonNull McpJsonObject inputSchemaDocument,
		@NonNull Optional<@NonNull McpJsonObject> outputSchemaDocument,
		@NonNull McpJsonObject descriptorFields,
		@NonNull McpJsonObject metadata) {
	@NonNull
	private static final Set<@NonNull String> RESERVED_DESCRIPTOR_FIELDS = Set.of(
			"name", "inputSchema", "outputSchema", "_meta");

	McpNormalizedToolDescriptor {
		name = McpProtocolSupport.requireNonBlank(name, "Tool name");
		requireNonNull(inputSchemaDocument);
		requireNonNull(outputSchemaDocument);
		descriptorFields = McpProtocolSupport.requireExtensionFields(
				descriptorFields, RESERVED_DESCRIPTOR_FIELDS);
		metadata = McpProtocolSupport.requireApplicationMetadataFields(
				metadata, Set.of());

		McpJsonValue inputType = inputSchemaDocument.members().get("type");
		if (!(inputType instanceof McpJsonString string)
				|| !"object".equals(string.value()))
			throw new IllegalArgumentException(
					"Tool input schema must directly declare type 'object'.");
	}

	@NonNull
	static McpNormalizedToolDescriptor minimal(@NonNull String name) {
		return new McpNormalizedToolDescriptor(name,
				new McpJsonObject(Map.of("type", new McpJsonString("object"))),
				Optional.empty(), McpJsonObject.empty(), McpJsonObject.empty());
	}

	@NonNull
	McpJsonObject toJsonObject() {
		Map<String, McpJsonValue> values = new LinkedHashMap<>();
		values.put("name", new McpJsonString(name));
		values.putAll(descriptorFields.members());
		values.put("inputSchema", inputSchemaDocument);
		outputSchemaDocument.ifPresent(value -> values.put("outputSchema", value));
		if (!metadata.members().isEmpty())
			values.put("_meta", metadata);
		return new McpJsonObject(values);
	}
}
