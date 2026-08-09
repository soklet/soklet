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
 * Immutable server-initiated request carried by an MCP
 * {@code input_required} result.
 *
 * <p>The declaration connects the emitted request to the capability and
 * registration metadata that permits it. Applications should reuse a
 * declaration registered through the operation's {@code mayRequestInput}
 * configuration. Soklet validates that relationship when it emits the
 * containing result.
 *
 * @param declaration registered input-request declaration
 * @param params method-specific request parameters
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public record McpInputRequest(
		@NonNull McpInputRequestDeclaration declaration,
		@NonNull McpJsonObject params) {
	/**
	 * Creates and validates an input request.
	 *
	 * @param declaration registered input-request declaration
	 * @param params method-specific request parameters
	 * @throws NullPointerException if an argument is null
	 */
	public McpInputRequest {
		requireNonNull(declaration);
		requireNonNull(params);
	}

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

	/**
	 * Returns the declared client request method.
	 *
	 * @return client request method
	 */
	@NonNull
	public String method() {
		return this.declaration.method();
	}

	/** @return rendering that identifies the method but redacts request parameters */
	@Override
	@NonNull
	public final String toString() {
		return "McpInputRequest{method='%s', params=<redacted>}"
				.formatted(method());
	}
}
