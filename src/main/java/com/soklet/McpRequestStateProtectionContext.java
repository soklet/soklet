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
import java.util.Arrays;

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
	private final String jsonRpcMethod;
	private final byte @NonNull [] associatedData;

	/**
	 * Creates a protection context from its components. This factory is useful
	 * for exercising application-provided request-state protectors in isolation;
	 * Soklet constructs the authoritative contexts used for live requests.
	 *
	 * @param endpointPath normalized endpoint path
	 * @param protocolVersion validated MCP protocol version
	 * @param jsonRpcMethod MCP JSON-RPC method
	 * @param associatedData canonical associated data, defensively copied
	 * @return immutable request-state protection context
	 * @throws NullPointerException if any argument is null
	 */
	@NonNull
	public static McpRequestStateProtectionContext fromComponents(
			@NonNull String endpointPath, @NonNull String protocolVersion,
			@NonNull String jsonRpcMethod,
			byte @NonNull [] associatedData) {
		return new McpRequestStateProtectionContext(endpointPath, protocolVersion,
				jsonRpcMethod, associatedData);
	}

	McpRequestStateProtectionContext(@NonNull String endpointPath,
			@NonNull String protocolVersion, @NonNull String jsonRpcMethod,
			byte @NonNull [] associatedData) {
		this.endpointPath = requireNonNull(endpointPath);
		this.protocolVersion = requireNonNull(protocolVersion);
		this.jsonRpcMethod = requireNonNull(jsonRpcMethod);
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
	public String getJsonRpcMethod() {
		return this.jsonRpcMethod;
	}

	/** @return defensive copy of the canonical associated data */
	public byte @NonNull [] getAssociatedData() {
		return this.associatedData.clone();
	}

	/**
	 * Compares the exact canonical protection inputs, including associated-data
	 * bytes by content.
	 *
	 * @return whether every protection-context property is structurally equal
	 */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpRequestStateProtectionContext context))
			return false;
		return this.endpointPath.equals(context.endpointPath)
				&& this.protocolVersion.equals(context.protocolVersion)
				&& this.jsonRpcMethod.equals(context.jsonRpcMethod)
				&& Arrays.equals(this.associatedData, context.associatedData);
	}

	/** @return structural protection-context hash code */
	@Override
	public int hashCode() {
		int result = this.endpointPath.hashCode();
		result = 31 * result + this.protocolVersion.hashCode();
		result = 31 * result + this.jsonRpcMethod.hashCode();
		result = 31 * result + Arrays.hashCode(this.associatedData);
		return result;
	}
}
