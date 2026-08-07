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

import javax.annotation.concurrent.ThreadSafe;

import static java.util.Objects.requireNonNull;

/**
 * Immutable framework-supplied context for an application request-state
 * protector.
 * <p>
 * Associated-data bytes are the exact versioned canonical binding that Soklet
 * can independently reconstruct for the original operation and its retry. A
 * custom protector must authenticate these bytes exactly. Neither they nor the
 * other values in this context are suitable for logs or metric dimensions.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpRequestStateProtectionContext {
	@NonNull
	private final String endpointPath;
	@NonNull
	private final String protocolVersion;
	@NonNull
	private final String method;
	private final byte @NonNull [] associatedData;

	McpRequestStateProtectionContext(@NonNull String endpointPath,
			@NonNull String protocolVersion, @NonNull String method,
			byte @NonNull [] associatedData) {
		this.endpointPath = requireNonNull(endpointPath);
		this.protocolVersion = requireNonNull(protocolVersion);
		this.method = requireNonNull(method);
		this.associatedData = requireNonNull(associatedData).clone();
	}

	/** @return normalized endpoint path */
	@NonNull
	public String getEndpointPath() {
		return this.endpointPath;
	}

	/** @return validated MCP protocol version supplied on this request */
	@NonNull
	public String getProtocolVersion() {
		return this.protocolVersion;
	}

	/** @return MCP JSON-RPC method */
	@NonNull
	public String getMethod() {
		return this.method;
	}

	/** @return defensive copy of the canonical associated data */
	public byte @NonNull [] getAssociatedData() {
		return this.associatedData.clone();
	}
}
