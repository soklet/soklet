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
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Bounded validation diagnostic that never includes an instance value.
 */
record McpSchemaDiagnostic(Code code, McpSchemaLocation schemaLocation,
		Optional<String> keyword, Optional<String> missingPropertyName,
		List<String> instancePointerSegments, String message) {
	enum Code {
		FALSE_SCHEMA,
		TYPE_MISMATCH,
		CONST_MISMATCH,
		ENUM_MISMATCH,
		REQUIRED_PROPERTY_MISSING
	}

	McpSchemaDiagnostic {
		requireNonNull(code);
		requireNonNull(schemaLocation);
		requireNonNull(keyword);
		keyword.ifPresent(java.util.Objects::requireNonNull);
		requireNonNull(missingPropertyName);
		missingPropertyName.ifPresent(java.util.Objects::requireNonNull);
		if ((code == Code.REQUIRED_PROPERTY_MISSING)
				!= missingPropertyName.isPresent())
			throw new IllegalArgumentException(
					"Only a required-property diagnostic names a missing property.");
		instancePointerSegments = List.copyOf(
				requireNonNull(instancePointerSegments));
		requireNonNull(message);
	}

	/**
	 * Exact internal accounting size: UTF-8 bytes for each exposed textual
	 * field plus one separator byte per field. This is not a wire format.
	 */
	int utf8ByteCount() {
		long bytes = utf8ByteCountUpTo(Integer.MAX_VALUE);
		if (bytes > Integer.MAX_VALUE)
			throw new IllegalStateException("A diagnostic accounting size overflowed.");
		return (int) bytes;
	}

	long utf8ByteCountUpTo(long maximum) {
		if (maximum < 0)
			throw new IllegalArgumentException("maximum must not be negative.");
		long bytes = 0;
		bytes = addField(code.name(), bytes, maximum);
		if (bytes > maximum)
			return bytes;
		bytes = addField(schemaLocation.retrievalUri().toASCIIString(), bytes,
				maximum);
		if (bytes > maximum)
			return bytes;
		bytes = addJsonPointerField(schemaLocation.pointerSegments(), bytes,
				maximum);
		if (bytes > maximum)
			return bytes;
		bytes = addField(keyword.orElse(""), bytes, maximum);
		if (bytes > maximum)
			return bytes;
		bytes = addField(missingPropertyName.orElse(""), bytes, maximum);
		if (bytes > maximum)
			return bytes;
		bytes = addField(message, bytes, maximum);
		if (bytes > maximum)
			return bytes;
		for (String segment : instancePointerSegments) {
			bytes = addField(segment, bytes, maximum);
			if (bytes > maximum)
				return bytes;
		}
		return bytes;
	}

	private static long addField(String value, long bytes, long maximum) {
		bytes = addUtf8Bytes(value, bytes, maximum);
		return bytes > maximum ? bytes : addBytes(bytes, 1, maximum);
	}

	private static long addJsonPointerField(List<String> segments, long bytes,
			long maximum) {
		for (String segment : segments) {
			bytes = addBytes(bytes, 1, maximum);
			if (bytes > maximum)
				return bytes;
			for (int index = 0; index < segment.length(); ++index) {
				char character = segment.charAt(index);
				if (character == '~' || character == '/') {
					bytes = addBytes(bytes, 2, maximum);
				} else {
					int encodedBytes;
					if (character <= 0x7F) {
						encodedBytes = 1;
					} else if (character <= 0x7FF) {
						encodedBytes = 2;
					} else if (Character.isHighSurrogate(character)) {
						if (index + 1 >= segment.length()
								|| !Character.isLowSurrogate(
										segment.charAt(index + 1)))
							throw new IllegalStateException(
									"A diagnostic pointer contains an unpaired high surrogate.");
						encodedBytes = 4;
						index++;
					} else if (Character.isLowSurrogate(character)) {
						throw new IllegalStateException(
								"A diagnostic pointer contains an unpaired low surrogate.");
					} else {
						encodedBytes = 3;
					}
					bytes = addBytes(bytes, encodedBytes, maximum);
				}
				if (bytes > maximum)
					return bytes;
			}
		}
		return addBytes(bytes, 1, maximum);
	}

	private static long addUtf8Bytes(String value, long bytes, long maximum) {
		for (int index = 0; index < value.length(); ++index) {
			char character = value.charAt(index);
			int encodedBytes;
			if (character <= 0x7F) {
				encodedBytes = 1;
			} else if (character <= 0x7FF) {
				encodedBytes = 2;
			} else if (Character.isHighSurrogate(character)) {
				if (index + 1 >= value.length()
						|| !Character.isLowSurrogate(value.charAt(index + 1)))
					throw new IllegalStateException(
							"A diagnostic contains an unpaired high surrogate.");
				encodedBytes = 4;
				index++;
			} else if (Character.isLowSurrogate(character)) {
				throw new IllegalStateException(
						"A diagnostic contains an unpaired low surrogate.");
			} else {
				encodedBytes = 3;
			}
			bytes = addBytes(bytes, encodedBytes, maximum);
			if (bytes > maximum)
				return bytes;
		}
		return bytes;
	}

	private static long addBytes(long bytes, int additional, long maximum) {
		if (bytes > maximum - additional)
			return maximum == Long.MAX_VALUE ? Long.MAX_VALUE : maximum + 1;
		return bytes + additional;
	}
}
