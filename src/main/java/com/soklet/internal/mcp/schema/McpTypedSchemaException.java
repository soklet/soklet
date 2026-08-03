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

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Stable typed-schema rejection with no instance-value reflection. */
final class McpTypedSchemaException extends IllegalArgumentException {
	enum Reason {
		INVALID_DESCRIPTOR,
		UNSUPPORTED_TYPE,
		RAW_GENERIC,
		WILDCARD,
		UNRESOLVED_TYPE_VARIABLE,
		UNRESOLVED_GENERIC_ARRAY_COMPONENT,
		OBJECT_TYPE,
		CHAR_SEQUENCE_TYPE,
		FRAMEWORK_TYPE,
		OPTIONAL_OUTSIDE_PROPERTY,
		INPUT_ROOT_NOT_OBJECT,
		AMBIGUOUS_OUTPUT_STRING,
		MAP_KEY_NOT_STRING,
		RECURSIVE_TYPE,
		DUPLICATE_PROPERTY,
		LIMIT_EXCEEDED
	}

	private final Reason reason;
	private final McpTypedSchemaPath path;
	private final Optional<McpSchemaCompilationException.Limit> limit;

	McpTypedSchemaException(Reason reason, String message,
			McpTypedSchemaPath path) {
		this(reason, message, path, Optional.empty());
	}

	McpTypedSchemaException(McpSchemaCompilationException.Limit limit,
			String message, McpTypedSchemaPath path) {
		this(Reason.LIMIT_EXCEEDED, message, path,
				Optional.of(requireNonNull(limit)));
	}

	private McpTypedSchemaException(Reason reason, String message,
			McpTypedSchemaPath path,
			Optional<McpSchemaCompilationException.Limit> limit) {
		super(requireNonNull(message));
		this.reason = requireNonNull(reason);
		this.path = requireNonNull(path);
		this.limit = requireNonNull(limit);
	}

	Reason reason() {
		return reason;
	}

	McpTypedSchemaPath path() {
		return path;
	}

	Optional<McpSchemaCompilationException.Limit> limit() {
		return limit;
	}
}
