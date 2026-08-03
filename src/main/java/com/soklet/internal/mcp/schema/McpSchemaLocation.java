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

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Decoded JSON Pointer segments within one Profile 1 document. */
record McpSchemaLocation(List<String> pointerSegments) {
	McpSchemaLocation {
		pointerSegments = List.copyOf(requireNonNull(pointerSegments));
	}

	static McpSchemaLocation root() {
		return new McpSchemaLocation(List.of());
	}

	McpSchemaLocation child(String... segments) {
		requireNonNull(segments);
		List<String> childSegments = new ArrayList<>(pointerSegments.size()
				+ segments.length);
		childSegments.addAll(pointerSegments);

		for (String segment : segments)
			childSegments.add(requireNonNull(segment));

		return new McpSchemaLocation(childSegments);
	}

	String jsonPointer() {
		StringBuilder pointer = new StringBuilder();

		for (String segment : pointerSegments)
			pointer.append('/').append(segment.replace("~", "~0").replace("/", "~1"));

		return pointer.toString();
	}
}
