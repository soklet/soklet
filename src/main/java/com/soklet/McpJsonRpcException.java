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

import javax.annotation.concurrent.NotThreadSafe;

import static java.util.Objects.requireNonNull;

/**
 * Signals an intentional client-visible JSON-RPC error from an MCP handler.
 *
 * <p>Unexpected application failures should be allowed to propagate normally
 * so Soklet can map them to its safe internal-error response.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public final class McpJsonRpcException extends RuntimeException {
	private static final long serialVersionUID = 1L;
	@NonNull
	private final McpJsonRpcError error;

	/**
	 * Creates an exception carrying an intentional JSON-RPC error.
	 *
	 * @param error client-visible JSON-RPC error
	 */
	public McpJsonRpcException(@NonNull McpJsonRpcError error) {
		super("MCP handler produced a JSON-RPC error.");
		this.error = requireNonNull(error);
	}

	/** @return client-visible JSON-RPC error */
	@NonNull
	public McpJsonRpcError getError() {
		return this.error;
	}
}
