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
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Typed, bounded failure produced while compiling a Profile 1 document.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
final class McpSchemaCompilationException extends IllegalArgumentException {
	enum Kind {
		INVALID_SCHEMA,
		INVALID_KEYWORD_VALUE,
		MISPLACED_DIALECT,
		UNSUPPORTED_DIALECT,
		UNSUPPORTED_KEYWORD,
		INVALID_ANCHOR,
		DUPLICATE_ANCHOR,
		INVALID_REFERENCE,
		UNRESOLVED_REFERENCE,
		LIMIT_EXCEEDED
	}

	enum Limit {
		SCHEMA_NODE_COUNT,
		SCHEMA_DEPTH,
		KEYWORD_COUNT,
		ANCHOR_COUNT,
		REFERENCE_COUNT,
		ANCHOR_NAME_LENGTH,
		REFERENCE_LENGTH,
		POINTER_SEGMENT_COUNT,
		COLLECTION_ENTRY_COUNT,
		NAME_LENGTH,
		POINTER_SEGMENT_LENGTH
	}

	@NonNull
	private final Kind kind;
	@NonNull
	private final Optional<@NonNull Limit> limit;
	@NonNull
	private final Optional<@NonNull McpSchemaLocation> location;
	@NonNull
	private final Optional<@NonNull String> keyword;

	McpSchemaCompilationException(@NonNull Kind kind, @NonNull String message,
			@Nullable McpSchemaLocation location, @Nullable String keyword) {
		this(kind, message, Optional.empty(), Optional.ofNullable(location),
				Optional.ofNullable(keyword));
	}

	McpSchemaCompilationException(@NonNull Limit limit,
			@NonNull String message,
			@Nullable McpSchemaLocation location, @Nullable String keyword) {
		this(Kind.LIMIT_EXCEEDED, message, Optional.of(requireNonNull(limit)),
				Optional.ofNullable(location), Optional.ofNullable(keyword));
	}

	private McpSchemaCompilationException(@NonNull Kind kind,
			@NonNull String message, @NonNull Optional<@NonNull Limit> limit,
			@NonNull Optional<@NonNull McpSchemaLocation> location,
			@NonNull Optional<@NonNull String> keyword) {
		super(requireNonNull(message));
		this.kind = requireNonNull(kind);
		this.limit = requireNonNull(limit);
		this.location = requireNonNull(location);
		this.keyword = requireNonNull(keyword);
	}

	@NonNull
	Kind kind() {
		return kind;
	}

	@NonNull
	Optional<@NonNull Limit> limit() {
		return limit;
	}

	@NonNull
	Optional<@NonNull McpSchemaLocation> location() {
		return location;
	}

	@NonNull
	Optional<@NonNull String> keyword() {
		return keyword;
	}
}
