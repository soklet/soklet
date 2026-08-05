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
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable executable form of one Soklet MCP Tool Schema Profile 1 node.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpToolSchemaProfileNode(@NonNull McpSchemaNodeId id,
		@NonNull McpSchemaLocation location,
		@NonNull Optional<@NonNull McpJsonBoolean> booleanSchema,
		@NonNull Set<@NonNull McpSchemaType> acceptedTypes,
		@NonNull Optional<@NonNull McpSchemaType> directType,
		@NonNull Optional<@NonNull McpJsonValue> constant,
		@NonNull Optional<@NonNull List<@NonNull McpJsonValue>> enumeration,
		@NonNull Map<@NonNull String, @NonNull McpSchemaNodeId> propertySchemas,
		@NonNull List<@NonNull String> requiredProperties,
		@NonNull Optional<@NonNull McpSchemaNodeId> additionalPropertiesSchema,
		@NonNull Optional<@NonNull McpSchemaNodeId> itemSchema,
		@NonNull List<@NonNull McpSchemaNodeId> allOfSchemas,
		@NonNull List<@NonNull McpSchemaNodeId> anyOfSchemas,
		@NonNull Optional<@NonNull McpSchemaNodeId> ifSchema,
		@NonNull Optional<@NonNull McpSchemaNodeId> thenSchema,
		@NonNull Optional<@NonNull McpSchemaNodeId> elseSchema,
		@NonNull Optional<@NonNull BigDecimal> minimum,
		@NonNull Optional<@NonNull BigDecimal> maximum,
		@NonNull Optional<@NonNull McpSchemaNodeId> referenceTarget) {
	McpToolSchemaProfileNode {
		requireNonNull(id);
		requireNonNull(location);
		requireNonNull(booleanSchema);
		requireNonNull(acceptedTypes);
		EnumSet<McpSchemaType> copiedTypes = acceptedTypes.isEmpty()
				? EnumSet.noneOf(McpSchemaType.class)
				: EnumSet.copyOf(acceptedTypes);
		acceptedTypes = Collections.unmodifiableSet(copiedTypes);
		requireNonNull(directType);
		if (directType.isPresent()
				&& !acceptedTypes.contains(directType.get()))
			throw new IllegalArgumentException(
					"A direct type must be present in the accepted type set.");
		requireNonNull(constant);
		requireNonNull(enumeration);
		enumeration = enumeration.map(List::copyOf);
		propertySchemas = Collections.unmodifiableMap(
				new LinkedHashMap<>(requireNonNull(propertySchemas)));
		requiredProperties = List.copyOf(requireNonNull(requiredProperties));
		requireNonNull(additionalPropertiesSchema);
		requireNonNull(itemSchema);
		allOfSchemas = List.copyOf(requireNonNull(allOfSchemas));
		anyOfSchemas = List.copyOf(requireNonNull(anyOfSchemas));
		requireNonNull(ifSchema);
		requireNonNull(thenSchema);
		requireNonNull(elseSchema);
		requireNonNull(minimum);
		requireNonNull(maximum);
		requireNonNull(referenceTarget);

		if (booleanSchema.isPresent()
				&& (!acceptedTypes.isEmpty() || directType.isPresent()
				|| constant.isPresent()
				|| enumeration.isPresent() || !propertySchemas.isEmpty()
				|| !requiredProperties.isEmpty()
				|| additionalPropertiesSchema.isPresent()
				|| itemSchema.isPresent() || !allOfSchemas.isEmpty()
				|| !anyOfSchemas.isEmpty() || ifSchema.isPresent()
				|| thenSchema.isPresent() || elseSchema.isPresent()
				|| minimum.isPresent() || maximum.isPresent()
				|| referenceTarget.isPresent()))
			throw new IllegalArgumentException(
					"A boolean schema cannot carry object-schema assertions.");
	}
}
