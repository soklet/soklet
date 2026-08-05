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

import javax.annotation.concurrent.NotThreadSafe;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Stable typed-schema rejection with no instance-value reflection.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
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

	@NonNull
	private final Reason reason;
	@NonNull
	private final McpTypedSchemaPath path;
	@NonNull
	private final Optional<McpSchemaCompilationException.@NonNull Limit> limit;

	McpTypedSchemaException(@NonNull Reason reason, @NonNull String message,
			@NonNull McpTypedSchemaPath path) {
		this(reason, message, path, Optional.empty());
	}

	McpTypedSchemaException(
			McpSchemaCompilationException.@NonNull Limit limit,
			@NonNull String message, @NonNull McpTypedSchemaPath path) {
		this(Reason.LIMIT_EXCEEDED, message, path,
				Optional.of(requireNonNull(limit)));
	}

	private McpTypedSchemaException(@NonNull Reason reason,
			@NonNull String message, @NonNull McpTypedSchemaPath path,
			@NonNull Optional<McpSchemaCompilationException.@NonNull Limit> limit) {
		super(requireNonNull(message));
		this.reason = requireNonNull(reason);
		this.path = requireNonNull(path);
		this.limit = requireNonNull(limit);
	}

	@NonNull
	Reason reason() {
		return reason;
	}

	@NonNull
	McpTypedSchemaPath path() {
		return path;
	}

	@NonNull
	Optional<McpSchemaCompilationException.@NonNull Limit> limit() {
		return limit;
	}
}
