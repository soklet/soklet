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
 * Thread-safe server-level hook that semantically sanitizes complete MCP tool
 * output before Soklet validates its output schema and writes it.
 * <p>
 * Soklet invokes the configured sanitizer after validating enough of the
 * result shape to identify a complete tool output, and before remaining result
 * validation, output-schema validation, envelope generation, and
 * serialization. It is not invoked for results that require additional client
 * input. The sanitized output, rather than the original output, traverses all
 * remaining validation.
 * <p>
 * Applications that need a sanitizer chain compose it behind this one hook and
 * own its ordering. A {@code null} return or thrown exception fails closed:
 * Soklet discards the original and partial output without exposing output
 * content or exception-derived data. Implementations must be safe for
 * concurrent invocation.
 * <p>
 * A sanitizer that changes only selected output fields should start from
 * {@link McpToolOutput#toBuilder()} so content order, structured content, and
 * error state are preserved unless changed deliberately.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpToolOutputSanitizer {
	/**
	 * Sanitizes one complete tool output.
	 *
	 * @param request immutable request context
	 * @param toolName invoked tool name
	 * @param rawArguments immutable raw tool arguments
	 * @param output complete unsanitized tool output
	 * @return non-null output to validate and serialize
	 * @throws Exception if application sanitization fails
	 * @see McpToolOutput#toBuilder()
	 */
	@NonNull
	McpToolOutput sanitize(@NonNull McpRequestContext request,
			@NonNull String toolName,
			@NonNull McpJsonObject rawArguments,
			@NonNull McpToolOutput output) throws Exception;

	/**
	 * Returns the shared sanitizer that preserves tool output unchanged.
	 *
	 * @return shared pass-through sanitizer
	 */
	@NonNull
	static McpToolOutputSanitizer passThroughInstance() {
		return PassThroughMcpToolOutputSanitizer.INSTANCE;
	}
}

/**
 * Thread-safe pass-through MCP tool-output sanitizer.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class PassThroughMcpToolOutputSanitizer
		implements McpToolOutputSanitizer {
	@NonNull
	static final PassThroughMcpToolOutputSanitizer INSTANCE =
			new PassThroughMcpToolOutputSanitizer();

	private PassThroughMcpToolOutputSanitizer() {
	}

	@Override
	@NonNull
	public McpToolOutput sanitize(@NonNull McpRequestContext request,
			@NonNull String toolName,
			@NonNull McpJsonObject rawArguments,
			@NonNull McpToolOutput output) {
		requireNonNull(request);
		requireNonNull(toolName);
		requireNonNull(rawArguments);
		return requireNonNull(output);
	}
}
