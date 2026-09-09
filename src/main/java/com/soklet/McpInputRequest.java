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

import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * Immutable server-initiated request carried by an MCP
 * {@code input_required} result.
 *
 * <p>The declaration connects the emitted request to the capability and
 * registration metadata that permits it. Applications should reuse a
 * declaration registered through the operation's
 * {@code addInputRequestDeclaration}
 * configuration. For a durable task, Soklet includes those declarations in
 * the task origin for the application to persist, then validates that
 * relationship before it emits the containing task snapshot.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpInputRequest {
	@NonNull
	private final McpInputRequestDeclaration declaration;
	@NonNull
	private final McpJsonObject params;

	/**
	 * Creates an input request from its registered declaration.
	 *
	 * @param declaration registered input-request declaration
	 * @param params method-specific request parameters
	 * @return immutable input request
	 * @throws NullPointerException if an argument is null
	 */
	@NonNull
	public static McpInputRequest fromDeclaration(
			@NonNull McpInputRequestDeclaration declaration,
			@NonNull McpJsonObject params) {
		return new McpInputRequest(declaration, params);
	}

	private McpInputRequest(@NonNull McpInputRequestDeclaration declaration,
			@NonNull McpJsonObject params) {
		this.declaration = requireNonNull(declaration);
		this.params = requireNonNull(params);
	}

	/** @return registered input-request declaration */
	@NonNull
	public McpInputRequestDeclaration getDeclaration() {
		return this.declaration;
	}

	/** @return method-specific request parameters */
	@NonNull
	public McpJsonObject getParams() {
		return this.params;
	}

	/**
	 * Returns the declared client request method.
	 *
	 * @return client request method
	 */
	@NonNull
	public String getMethod() {
		return this.declaration.getJsonRpcMethod();
	}

	/**
	 * Reports whether a client response matches this request's declared MCP
	 * input-response union branch.
	 *
	 * <p>This validates the protocol-level response shape for the declaration.
	 * It does not validate accepted elicitation content against this request's
	 * requested schema or against application-specific policy.
	 *
	 * @param inputResponse client response to inspect
	 * @return whether the response matches the declared MCP union branch
	 * @throws NullPointerException if {@code inputResponse} is null
	 */
	@NonNull
	public Boolean matchesInputResponse(@NonNull McpJsonValue inputResponse) {
		return McpServerRuntimeBridge.matchesInputResponse(this.declaration,
				requireNonNull(inputResponse));
	}

	void requireValidParams() {
		McpServerRuntimeBridge.requireValidInputRequestParams(this.declaration,
				this.params);
	}

	/** @return whether this value has the same declaration and parameters */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpInputRequest request))
			return false;
		return this.declaration.equals(request.declaration)
				&& this.params.equals(request.params);
	}

	/** @return value-based hash code */
	@Override
	public int hashCode() {
		return Objects.hash(this.declaration, this.params);
	}

	/** @return rendering that identifies the method but redacts request parameters */
	@Override
	@NonNull
	public final String toString() {
		return "McpInputRequest{method='%s', params=<redacted>}"
				.formatted(getMethod());
	}
}
