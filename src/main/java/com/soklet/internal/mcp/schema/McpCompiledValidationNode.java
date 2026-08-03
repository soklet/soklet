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

import com.soklet.internal.mcp.protocol.McpJsonBoolean;
import com.soklet.internal.mcp.protocol.McpJsonValue;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable executable assertions and conjunctive edges for one compiled
 * schema node.
 */
record McpCompiledValidationNode(McpSchemaNodeId id,
		McpSchemaLocation location, Optional<McpJsonBoolean> booleanSchema,
		Set<McpSchemaType> acceptedTypes, Optional<McpJsonValue> constant,
		Optional<List<McpJsonValue>> enumeration,
		Map<String, McpSchemaNodeId> propertySchemas,
		List<String> requiredProperties,
		List<McpSchemaNodeId> allOfSchemas,
		Optional<McpSchemaNodeId> referenceTarget) {
	McpCompiledValidationNode {
		requireNonNull(id);
		requireNonNull(location);
		requireNonNull(booleanSchema);
		requireNonNull(acceptedTypes);
		EnumSet<McpSchemaType> copiedTypes = acceptedTypes.isEmpty()
				? EnumSet.noneOf(McpSchemaType.class) : EnumSet.copyOf(acceptedTypes);
		acceptedTypes = Collections.unmodifiableSet(copiedTypes);
		requireNonNull(constant);
		requireNonNull(enumeration);
		enumeration = enumeration.map(List::copyOf);
		propertySchemas = Collections.unmodifiableMap(
				new LinkedHashMap<>(requireNonNull(propertySchemas)));
		requiredProperties = List.copyOf(requireNonNull(requiredProperties));
		allOfSchemas = List.copyOf(requireNonNull(allOfSchemas));
		requireNonNull(referenceTarget);

		if (booleanSchema.isPresent()
				&& (!acceptedTypes.isEmpty() || constant.isPresent()
				|| enumeration.isPresent() || !propertySchemas.isEmpty()
				|| !requiredProperties.isEmpty() || !allOfSchemas.isEmpty()
				|| referenceTarget.isPresent()))
			throw new IllegalArgumentException(
					"A boolean schema cannot carry object-schema assertions.");
	}
}
