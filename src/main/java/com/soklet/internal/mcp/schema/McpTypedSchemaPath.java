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

/** Stable logical path into a typed Java shape. */
record McpTypedSchemaPath(List<Segment> segments) {
	enum SegmentKind {
		PROPERTY,
		ARRAY_ELEMENT,
		MAP_VALUE,
		OPTIONAL_VALUE,
		GENERIC_ARGUMENT
	}

	record Segment(SegmentKind kind, String value) {
		Segment {
			requireNonNull(kind);
			requireNonNull(value);
		}
	}

	McpTypedSchemaPath {
		segments = List.copyOf(requireNonNull(segments));
		for (Segment segment : segments)
			requireNonNull(segment);
	}

	static McpTypedSchemaPath root() {
		return new McpTypedSchemaPath(List.of());
	}

	McpTypedSchemaPath property(String name) {
		return append(new Segment(SegmentKind.PROPERTY, requireNonNull(name)));
	}

	McpTypedSchemaPath arrayElement() {
		return append(new Segment(SegmentKind.ARRAY_ELEMENT, "items"));
	}

	McpTypedSchemaPath mapValue() {
		return append(new Segment(SegmentKind.MAP_VALUE,
				"additionalProperties"));
	}

	McpTypedSchemaPath optionalValue() {
		return append(new Segment(SegmentKind.OPTIONAL_VALUE, "optional"));
	}

	McpTypedSchemaPath genericArgument(int index) {
		if (index < 0)
			throw new IllegalArgumentException(
					"Generic-argument index must not be negative.");
		return append(new Segment(SegmentKind.GENERIC_ARGUMENT,
				Integer.toString(index)));
	}

	private McpTypedSchemaPath append(Segment segment) {
		List<Segment> appended = new ArrayList<>(segments.size() + 1);
		appended.addAll(segments);
		appended.add(requireNonNull(segment));
		return new McpTypedSchemaPath(appended);
	}

	@Override
	public String toString() {
		StringBuilder result = new StringBuilder("$");
		for (Segment segment : segments) {
			switch (segment.kind()) {
				case PROPERTY -> result.append("/properties/")
						.append(escapePointerSegment(segment.value()));
				case ARRAY_ELEMENT -> result.append("/items");
				case MAP_VALUE -> result.append("/additionalProperties");
				case OPTIONAL_VALUE -> result.append("/optional");
				case GENERIC_ARGUMENT -> result.append("/genericArguments/")
						.append(segment.value());
			}
		}
		return result.toString();
	}

	private static String escapePointerSegment(String value) {
		return value.replace("~", "~0").replace("/", "~1");
	}
}
