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
import java.math.BigDecimal;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable wrapper for JSON-RPC request identifiers.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Immutable
public record McpJsonRpcRequestId(
		@NonNull McpValue value
) {
	public McpJsonRpcRequestId {
		requireNonNull(value);

		if (!(value instanceof McpString) && !(value instanceof McpNumber))
			throw new IllegalArgumentException("JSON-RPC request IDs must be strings or numbers.");
	}

	@NonNull
	public static McpJsonRpcRequestId fromString(@NonNull String value) {
		requireNonNull(value);
		return new McpJsonRpcRequestId(new McpString(value));
	}

	@NonNull
	public static McpJsonRpcRequestId fromNumber(@NonNull BigDecimal value) {
		requireNonNull(value);
		return new McpJsonRpcRequestId(new McpNumber(value));
	}

	@NonNull
	public Optional<String> asString() {
		return value() instanceof McpString mcpString ? Optional.of(mcpString.value()) : Optional.empty();
	}

	@NonNull
	public Optional<BigDecimal> asNumber() {
		return value() instanceof McpNumber mcpNumber ? Optional.of(mcpNumber.value()) : Optional.empty();
	}
}
