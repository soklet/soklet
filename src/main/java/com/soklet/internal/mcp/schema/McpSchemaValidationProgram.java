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

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Immutable, thread-safe validation program for the currently supported
 * Draft 2020-12 keyword slice.
 */
record McpSchemaValidationProgram(McpSchemaResourceGraph resourceGraph,
		List<McpCompiledValidationNode> nodes) {
	McpSchemaValidationProgram {
		requireNonNull(resourceGraph);
		nodes = List.copyOf(requireNonNull(nodes));
		if (nodes.size() != resourceGraph.nodes().size())
			throw new IllegalArgumentException(
					"Every resource-graph node must have one validation node.");
		for (int index = 0; index < nodes.size(); ++index) {
			if (nodes.get(index).id().value() != index)
				throw new IllegalArgumentException(
						"Validation node IDs must match their immutable list indexes.");
		}
	}

	McpCompiledValidationNode node(McpSchemaNodeId id) {
		return nodes.get(requireNonNull(id).value());
	}
}
