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

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable, completely offline graph of compiled schema resources.
 *
 * <p>Pointer indexes use each resource's canonical URI and do not retain
 * fragile enclosing-resource pointers that cross an embedded-resource
 * boundary.</p>
 */
record McpSchemaResourceGraph(List<McpCompiledSchemaNode> nodes,
		List<McpSchemaResource> resources,
		Map<URI, McpSchemaResourceId> resourceIdentifiers,
		Map<URI, McpSchemaNodeId> documentRoots) {
	McpSchemaResourceGraph {
		nodes = List.copyOf(requireNonNull(nodes));
		resources = List.copyOf(requireNonNull(resources));
		resourceIdentifiers = immutableMap(resourceIdentifiers);
		documentRoots = immutableMap(documentRoots);

		for (int index = 0; index < nodes.size(); ++index) {
			if (nodes.get(index).id().value() != index)
				throw new IllegalArgumentException(
						"Schema node IDs must match their immutable list indexes.");
		}

		for (int index = 0; index < resources.size(); ++index) {
			if (resources.get(index).id().value() != index)
				throw new IllegalArgumentException(
						"Schema resource IDs must match their immutable list indexes.");
		}
	}

	McpCompiledSchemaNode node(McpSchemaNodeId nodeId) {
		requireNonNull(nodeId);
		return nodes.get(nodeId.value());
	}

	McpSchemaResource resource(McpSchemaResourceId resourceId) {
		requireNonNull(resourceId);
		return resources.get(resourceId.value());
	}

	Optional<McpSchemaResource> resource(URI identifier) {
		requireNonNull(identifier);
		McpSchemaResourceId resourceId = resourceIdentifiers.get(identifier);
		return resourceId == null ? Optional.empty()
				: Optional.of(resource(resourceId));
	}

	private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
		return Collections.unmodifiableMap(
				new LinkedHashMap<>(requireNonNull(source)));
	}
}
