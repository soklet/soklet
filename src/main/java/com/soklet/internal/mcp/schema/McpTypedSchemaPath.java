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

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Stable logical path into a typed Java shape.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpTypedSchemaPath(@NonNull List<@NonNull Segment> segments) {
	@NonNull
	private static final String HEXADECIMAL_DIGITS = "0123456789ABCDEF";
	private static final int MAXIMUM_ESCAPED_SEGMENT_PREFIX_LENGTH = 256;

	enum SegmentKind {
		PROPERTY,
		ARRAY_ELEMENT,
		MAP_VALUE,
		OPTIONAL_VALUE,
		GENERIC_ARGUMENT
	}

	record Segment(@NonNull SegmentKind kind, @NonNull String value) {
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

	@NonNull
	static McpTypedSchemaPath root() {
		return new McpTypedSchemaPath(List.of());
	}

	@NonNull
	McpTypedSchemaPath property(@NonNull String name) {
		return append(new Segment(SegmentKind.PROPERTY, requireNonNull(name)));
	}

	@NonNull
	McpTypedSchemaPath arrayElement() {
		return append(new Segment(SegmentKind.ARRAY_ELEMENT, "items"));
	}

	@NonNull
	McpTypedSchemaPath mapValue() {
		return append(new Segment(SegmentKind.MAP_VALUE,
				"additionalProperties"));
	}

	@NonNull
	McpTypedSchemaPath optionalValue() {
		return append(new Segment(SegmentKind.OPTIONAL_VALUE, "optional"));
	}

	@NonNull
	McpTypedSchemaPath genericArgument(int index) {
		if (index < 0)
			throw new IllegalArgumentException(
					"Generic-argument index must not be negative.");
		return append(new Segment(SegmentKind.GENERIC_ARGUMENT,
				Integer.toString(index)));
	}

	@NonNull
	private McpTypedSchemaPath append(@NonNull Segment segment) {
		List<@NonNull Segment> appended = new ArrayList<>(segments.size() + 1);
		appended.addAll(segments);
		appended.add(requireNonNull(segment));
		return new McpTypedSchemaPath(appended);
	}

	@Override
	@NonNull
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

	@NonNull
	private static String escapePointerSegment(@NonNull String value) {
		StringBuilder result = new StringBuilder(value.length());
		for (int index = 0; index < value.length();) {
			char first = value.charAt(index);
			int codePoint;
			int characterCount;
			if (Character.isHighSurrogate(first)
					&& index + 1 < value.length()
					&& Character.isLowSurrogate(value.charAt(index + 1))) {
				codePoint = Character.toCodePoint(first,
						value.charAt(index + 1));
				characterCount = 2;
			} else {
				codePoint = first;
				characterCount = 1;
			}
			int renderedLength = codePoint == '~' || codePoint == '/'
					? 2 : requiresDiagnosticEscape(codePoint)
					? 6 * characterCount : characterCount;
			if (result.length() + renderedLength
					> MAXIMUM_ESCAPED_SEGMENT_PREFIX_LENGTH) {
				result.append("...");
				break;
			}

			if (codePoint == '~')
				result.append("~0");
			else if (codePoint == '/')
				result.append("~1");
			else if (requiresDiagnosticEscape(codePoint)) {
				for (int offset = 0; offset < characterCount; ++offset)
					appendUnicodeEscape(result, value.charAt(index + offset));
			} else
				result.append(value, index, index + characterCount);
			index += characterCount;
		}
		return result.toString();
	}

	private static boolean requiresDiagnosticEscape(int codePoint) {
		int type = Character.getType(codePoint);
		return Character.isISOControl(codePoint)
				|| type == Character.FORMAT
				|| type == Character.LINE_SEPARATOR
				|| type == Character.PARAGRAPH_SEPARATOR
				|| type == Character.SURROGATE;
	}

	private static void appendUnicodeEscape(@NonNull StringBuilder result,
			char value) {
		result.append("\\u")
				.append(HEXADECIMAL_DIGITS.charAt((value >>> 12) & 0xF))
				.append(HEXADECIMAL_DIGITS.charAt((value >>> 8) & 0xF))
				.append(HEXADECIMAL_DIGITS.charAt((value >>> 4) & 0xF))
				.append(HEXADECIMAL_DIGITS.charAt(value & 0xF));
	}
}
