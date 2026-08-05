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

import com.soklet.internal.mcp.protocol.McpJsonObject;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Immutable compiled Soklet MCP Tool Schema Profile 1 document.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpToolSchemaProfileProgram(@NonNull McpJsonObject document,
		@NonNull McpSchemaNodeId rootNodeId,
		@NonNull List<@NonNull McpToolSchemaProfileNode> nodes,
		@NonNull Map<@NonNull String, @NonNull String> declaredHeadersBySchemaPointer) {
	McpToolSchemaProfileProgram {
		requireNonNull(document);
		requireNonNull(rootNodeId);
		nodes = List.copyOf(requireNonNull(nodes));
		declaredHeadersBySchemaPointer = Collections.unmodifiableMap(
				new LinkedHashMap<>(requireNonNull(
						declaredHeadersBySchemaPointer)));
		if (nodes.isEmpty() || rootNodeId.value() >= nodes.size())
			throw new IllegalArgumentException("The root schema node is missing.");
		for (int index = 0; index < nodes.size(); ++index) {
			if (nodes.get(index).id().value() != index)
				throw new IllegalArgumentException(
						"Profile node IDs must match their immutable list indexes.");
		}
	}

	@NonNull
	McpToolSchemaProfileNode node(@NonNull McpSchemaNodeId id) {
		return nodes.get(requireNonNull(id).value());
	}
}
