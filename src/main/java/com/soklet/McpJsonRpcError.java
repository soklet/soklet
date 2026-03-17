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

package com.soklet;

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.Immutable;

import static java.util.Objects.requireNonNull;

/**
 * Immutable JSON-RPC error descriptor used by MCP request handling.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Immutable
public record McpJsonRpcError(
		@NonNull Integer code,
		@NonNull String message
) {
	public McpJsonRpcError {
		requireNonNull(code);
		requireNonNull(message);
	}

	/**
	 * Creates a JSON-RPC error descriptor from a code and message.
	 *
	 * @param code the JSON-RPC error code
	 * @param message the JSON-RPC error message
	 * @return a new JSON-RPC error descriptor
	 */
	@NonNull
	public static McpJsonRpcError fromCodeAndMessage(@NonNull Integer code,
																									 @NonNull String message) {
		requireNonNull(code);
		requireNonNull(message);
		return new McpJsonRpcError(code, message);
	}
}
