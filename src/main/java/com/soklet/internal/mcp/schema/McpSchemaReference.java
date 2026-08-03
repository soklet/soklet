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
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * A statically resolved reference edge in a compiled schema graph.
 */
record McpSchemaReference(Kind kind, URI resolvedUri,
		McpSchemaNodeId initialTargetNodeId, Optional<String> dynamicAnchorName) {
	enum Kind {
		STATIC,
		DYNAMIC
	}

	McpSchemaReference {
		requireNonNull(kind);
		requireNonNull(resolvedUri);
		requireNonNull(initialTargetNodeId);
		requireNonNull(dynamicAnchorName);

		if (kind == Kind.STATIC && dynamicAnchorName.isPresent())
			throw new IllegalArgumentException(
					"A static reference cannot carry a dynamic anchor name.");
	}
}
