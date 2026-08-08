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
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable client-visible JSON-RPC error used by MCP application policies,
 * handlers, and request lifecycle observation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpJsonRpcError {
	/**
	 * Soklet-owned error code used for rate-limit rejection results.
	 */
	public static final int SOKLET_RATE_LIMIT_ERROR_CODE = -31999;
	/**
	 * Soklet-owned error code used when strict unknown mirrored-header handling
	 * rejects a request.
	 */
	public static final int SOKLET_STRICT_UNKNOWN_MIRRORED_HEADER_ERROR_CODE = -31998;
	private static final int INVALID_PARAMS = -32602;
	private final int code;
	@NonNull
	private final String message;
	@Nullable
	private final McpJsonValue data;

	/**
	 * Creates an application-defined JSON-RPC error without a {@code data}
	 * member.
	 *
	 * @param code    an application-owned error code
	 * @param message the nonblank client-visible message
	 * @return the JSON-RPC error
	 * @throws NullPointerException if {@code code} is null
	 * @throws IllegalArgumentException if the code is reserved by JSON-RPC, MCP,
	 *                                  or Soklet, or the message is blank
	 */
	@NonNull
	public static McpJsonRpcError fromApplication(@NonNull Integer code,
			@NonNull String message) {
		return new McpJsonRpcError(requireApplicationCode(requireNonNull(code)),
				message, null);
	}

	/**
	 * Creates an application-defined JSON-RPC error with a {@code data} member.
	 *
	 * @param code    an application-owned error code
	 * @param message the nonblank client-visible message
	 * @param data    structured client-visible error data
	 * @return the JSON-RPC error
	 * @throws NullPointerException if {@code code} is null
	 * @throws IllegalArgumentException if the code is reserved by JSON-RPC, MCP,
	 *                                  or Soklet, or the message is blank
	 */
	@NonNull
	public static McpJsonRpcError fromApplication(@NonNull Integer code,
			@NonNull String message,
			@NonNull McpJsonValue data) {
		return new McpJsonRpcError(requireApplicationCode(requireNonNull(code)), message,
				requireNonNull(data));
	}

	/**
	 * Creates a standard JSON-RPC invalid-parameters error without a
	 * {@code data} member.
	 *
	 * @param message the nonblank client-visible message
	 * @return the invalid-parameters error
	 * @throws IllegalArgumentException if the message is blank
	 */
	@NonNull
	public static McpJsonRpcError fromInvalidParameters(@NonNull String message) {
		return new McpJsonRpcError(INVALID_PARAMS, message, null);
	}

	/**
	 * Creates a standard JSON-RPC invalid-parameters error with a {@code data}
	 * member.
	 *
	 * @param message the nonblank client-visible message
	 * @param data    structured client-visible error data
	 * @return the invalid-parameters error
	 * @throws IllegalArgumentException if the message is blank
	 */
	@NonNull
	public static McpJsonRpcError fromInvalidParameters(@NonNull String message,
			@NonNull McpJsonValue data) {
		return new McpJsonRpcError(INVALID_PARAMS, message, requireNonNull(data));
	}

	private McpJsonRpcError(int code, @NonNull String message,
			@Nullable McpJsonValue data) {
		this.code = code;
		this.message = requireNonNull(message);
		if (message.isBlank())
			throw new IllegalArgumentException("message must not be blank");
		this.data = data;
	}

	@NonNull
	static McpJsonRpcError fromServer(int code, @NonNull String message,
			@Nullable McpJsonValue data) {
		return new McpJsonRpcError(code, message, data);
	}

	/**
	 * The JSON-RPC error code.
	 *
	 * @return the error code
	 */
	@NonNull
	public Integer getCode() {
		return this.code;
	}

	/**
	 * The nonblank client-visible error message.
	 *
	 * @return the error message
	 */
	@NonNull
	public String getMessage() {
		return this.message;
	}

	/**
	 * Optional structured client-visible error data.
	 *
	 * @return the error data, or the empty optional if absent
	 */
	@NonNull
	public Optional<@NonNull McpJsonValue> getData() {
		return Optional.ofNullable(this.data);
	}

	private static int requireApplicationCode(int code) {
		if ((code >= -32768 && code <= -32000)
				|| code == SOKLET_RATE_LIMIT_ERROR_CODE
				|| code == SOKLET_STRICT_UNKNOWN_MIRRORED_HEADER_ERROR_CODE)
			throw new IllegalArgumentException(
					"code is reserved by JSON-RPC, MCP, or Soklet");
		return code;
	}
}
