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

import com.soklet.internal.mcp.protocol.McpJsonValue;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable schema node with graph edges represented by stable IDs.
 */
record McpCompiledSchemaNode(McpSchemaNodeId id, McpSchemaLocation location,
		McpSchemaResourceId resourceId, List<String> resourcePointerSegments,
		McpJsonValue schema,
		List<McpSchemaNodeId> childNodeIds,
		Optional<McpSchemaReference> reference,
		Optional<McpSchemaReference> dynamicReference) {
	McpCompiledSchemaNode {
		requireNonNull(id);
		requireNonNull(location);
		requireNonNull(resourceId);
		resourcePointerSegments = List.copyOf(requireNonNull(resourcePointerSegments));
		requireNonNull(schema);
		childNodeIds = List.copyOf(requireNonNull(childNodeIds));
		requireNonNull(reference);
		requireNonNull(dynamicReference);
	}
}
