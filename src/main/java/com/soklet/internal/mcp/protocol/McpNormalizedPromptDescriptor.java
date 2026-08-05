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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable prompt catalog projection derived from the same registration plan
 * that owns execution.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpNormalizedPromptDescriptor(@NonNull String name,
		@NonNull List<@NonNull McpNormalizedPromptArgumentDescriptor> arguments,
		@NonNull McpJsonObject descriptorFields,
		@NonNull McpJsonObject metadata) {
	@NonNull
	private static final Set<@NonNull String> RESERVED_DESCRIPTOR_FIELDS = Set.of(
			"name", "arguments", "_meta");

	McpNormalizedPromptDescriptor {
		name = McpProtocolSupport.requireNonBlank(name, "Prompt name");
		arguments = List.copyOf(requireNonNull(arguments));
		descriptorFields = McpProtocolSupport.requireExtensionFields(
				descriptorFields, RESERVED_DESCRIPTOR_FIELDS);
		metadata = McpProtocolSupport.requireApplicationMetadataFields(
				metadata, Set.of());

		Set<String> argumentNames = new LinkedHashSet<>();
		for (McpNormalizedPromptArgumentDescriptor argument : arguments) {
			requireNonNull(argument);
			if (!argumentNames.add(argument.name()))
				throw new IllegalArgumentException(
						"Duplicate prompt argument '" + argument.name() + "'.");
		}
	}

	@NonNull
	static McpNormalizedPromptDescriptor minimal(@NonNull String name) {
		return new McpNormalizedPromptDescriptor(name, List.of(),
				McpJsonObject.empty(), McpJsonObject.empty());
	}

	@NonNull
	McpJsonObject toJsonObject() {
		Map<String, McpJsonValue> values = new LinkedHashMap<>();
		values.put("name", new McpJsonString(name));
		values.putAll(descriptorFields.members());
		if (!arguments.isEmpty()) {
			List<McpJsonValue> argumentValues = arguments.stream()
					.map(McpNormalizedPromptArgumentDescriptor::toJsonObject)
					.map(McpJsonValue.class::cast)
					.toList();
			values.put("arguments", new McpJsonArray(argumentValues));
		}
		if (!metadata.members().isEmpty())
			values.put("_meta", metadata);
		return new McpJsonObject(values);
	}
}

/**
 * One immutable string-argument declaration in a prompt descriptor.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpNormalizedPromptArgumentDescriptor(@NonNull String name,
		boolean required, @NonNull McpJsonObject descriptorFields) {
	@NonNull
	private static final Set<@NonNull String> RESERVED_DESCRIPTOR_FIELDS = Set.of(
			"name", "required");

	McpNormalizedPromptArgumentDescriptor {
		name = McpProtocolSupport.requireNonBlank(name, "Prompt argument name");
		descriptorFields = McpProtocolSupport.requireExtensionFields(
				descriptorFields, RESERVED_DESCRIPTOR_FIELDS);
	}

	@NonNull
	McpJsonObject toJsonObject() {
		Map<String, McpJsonValue> values = new LinkedHashMap<>();
		values.put("name", new McpJsonString(name));
		values.putAll(descriptorFields.members());
		if (required)
			values.put("required", McpJsonBoolean.TRUE);
		return new McpJsonObject(values);
	}
}
