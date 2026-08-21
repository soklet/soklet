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

package com.soklet.internal.mcp.protocol;

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Typed protocol-decoding failure for later deterministic transport mapping.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
final class McpWireDecodingException extends IllegalArgumentException {
	enum Kind {
		PARSE_ERROR,
		INVALID_REQUEST,
		INVALID_PARAMS
	}

	@NonNull
	private final Kind kind;
	@NonNull
	private final Optional<@NonNull McpJsonRpcId> readableRequestId;
	@NonNull
	private final Optional<@NonNull String> readableMethod;

	private McpWireDecodingException(@NonNull Kind kind, @NonNull String message,
			@NonNull Optional<@NonNull McpJsonRpcId> readableRequestId,
			@NonNull Optional<@NonNull String> readableMethod) {
		super(requireNonNull(message));
		this.kind = requireNonNull(kind);
		this.readableRequestId = requireNonNull(readableRequestId);
		this.readableMethod = requireNonNull(readableMethod);
	}

	private McpWireDecodingException(@NonNull Kind kind, @NonNull String message,
			@NonNull Optional<@NonNull McpJsonRpcId> readableRequestId,
			@NonNull Optional<@NonNull String> readableMethod,
			@NonNull Throwable cause) {
		super(requireNonNull(message), requireNonNull(cause));
		this.kind = requireNonNull(kind);
		this.readableRequestId = requireNonNull(readableRequestId);
		this.readableMethod = requireNonNull(readableMethod);
	}

	@NonNull
	static McpWireDecodingException parseError(@NonNull Throwable cause) {
		return new McpWireDecodingException(Kind.PARSE_ERROR,
				"The request body is not valid JSON.", Optional.empty(),
				Optional.empty(),
				requireNonNull(cause));
	}

	@NonNull
	static McpWireDecodingException invalidRequest(@NonNull String message,
			@NonNull Optional<@NonNull McpJsonRpcId> readableRequestId,
			@NonNull Optional<@NonNull String> readableMethod) {
		return new McpWireDecodingException(Kind.INVALID_REQUEST, message,
				readableRequestId, readableMethod);
	}

	@NonNull
	static McpWireDecodingException invalidParams(@NonNull String message,
			@NonNull McpJsonRpcId readableRequestId) {
		return new McpWireDecodingException(Kind.INVALID_PARAMS, message,
				Optional.of(requireNonNull(readableRequestId)), Optional.empty());
	}

	@NonNull
	Kind kind() {
		return kind;
	}

	@NonNull
	Optional<@NonNull McpJsonRpcId> readableRequestId() {
		return readableRequestId;
	}

	@NonNull
	Optional<@NonNull String> readableMethod() {
		return readableMethod;
	}
}
