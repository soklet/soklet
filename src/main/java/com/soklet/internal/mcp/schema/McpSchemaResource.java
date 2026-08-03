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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * One immutable Draft 2020-12 schema resource and its local identifiers.
 */
record McpSchemaResource(McpSchemaResourceId id, URI canonicalUri,
		Set<URI> identifiers, McpSchemaNodeId rootNodeId, URI dialectUri,
		Optional<McpSchemaResourceId> enclosingResourceId,
		Map<String, McpSchemaNodeId> anchors,
		Map<String, McpSchemaNodeId> dynamicAnchors,
		Map<List<String>, McpSchemaNodeId> pointerTargets) {
	McpSchemaResource {
		requireNonNull(id);
		requireNonNull(canonicalUri);
		identifiers = Collections.unmodifiableSet(
				new LinkedHashSet<>(requireNonNull(identifiers)));
		requireNonNull(rootNodeId);
		requireNonNull(dialectUri);
		requireNonNull(enclosingResourceId);
		anchors = Collections.unmodifiableMap(
				new LinkedHashMap<>(requireNonNull(anchors)));
		dynamicAnchors = Collections.unmodifiableMap(
				new LinkedHashMap<>(requireNonNull(dynamicAnchors)));

		Map<List<String>, McpSchemaNodeId> copiedPointerTargets =
				new LinkedHashMap<>();
		for (Map.Entry<List<String>, McpSchemaNodeId> entry
				: requireNonNull(pointerTargets).entrySet())
			copiedPointerTargets.put(List.copyOf(requireNonNull(entry.getKey())),
					requireNonNull(entry.getValue()));
		pointerTargets = Collections.unmodifiableMap(copiedPointerTargets);
	}
}
