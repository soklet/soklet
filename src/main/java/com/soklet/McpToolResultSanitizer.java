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
 * Thread-safe server-level hook that sanitizes completed MCP tool results,
 * including their payload and application-owned result metadata.
 *
 * <p>Soklet invokes the hook after handler/interceptor processing and after
 * checking that the payload is {@link McpToolOutput}, but before remaining
 * result validation, output-schema validation, structured-content mirroring,
 * envelope construction, and serialization. Only the returned result's payload
 * and metadata are used by subsequent validation, budgets, and writing.
 * Exceptions, null results, or non-tool payloads fail closed without exposing
 * original or partial result data or exception-derived details.
 *
 * <p>A completed durable tool task is sanitized again on each authorized
 * detailed {@code tasks/get} request, potentially on different server nodes.
 * The supplied context belongs to the independently admitted polling request;
 * the original tool name and raw arguments come from persisted task origin.
 * The caller-specific result does not replace the persisted result.
 * Implementations must be safe for concurrent invocation, deterministic and
 * idempotent for repeated equivalent calls, and free of one-shot side effects.
 * Applications may compose a sanitizer chain behind this one hook.
 *
 * <p>For selective changes, start with {@link McpCompleteResult#toBuilder()}
 * and, when changing tool output, {@link McpToolOutput#toBuilder()}. Result
 * metadata is out-of-band data, not a secret channel; a host may log or forward
 * it. Neither this hook nor Apps metadata grants authorization. This hook does
 * not sanitize task-status metadata, progress notifications, input-required
 * results, or every JSON-RPC response, and does not translate application JSON.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpToolResultSanitizer {

	/**
	 * Sanitizes one complete tool result.
	 *
	 * @param requestContext immutable current request context; for completed
	 *                       durable tasks, the authorized polling request
	 * @param toolName original invoked tool name
	 * @param rawArguments immutable original raw tool arguments
	 * @param completeResult unsanitized complete result with a tool payload
	 * @return non-null result with a tool payload, to validate and serialize
	 * @throws Exception if application sanitization fails
	 */
	@NonNull
	McpCompleteResult sanitize(@NonNull McpRequestContext requestContext,
			@NonNull String toolName,
			@NonNull McpJsonObject rawArguments,
			@NonNull McpCompleteResult completeResult) throws Exception;

	/**
	 * Returns the shared sanitizer that leaves the supplied result unchanged.
	 * No application-level redaction is performed; normal framework validation
	 * and output limits still apply.
	 *
	 * @return shared unchanged-result sanitizer
	 */
	@NonNull
	static McpToolResultSanitizer nonSanitizingInstance() {
		return NonSanitizingMcpToolResultSanitizer.INSTANCE;
	}
}

/** Thread-safe unchanged-result implementation. */
@ThreadSafe
final class NonSanitizingMcpToolResultSanitizer implements McpToolResultSanitizer {
	@NonNull
	static final NonSanitizingMcpToolResultSanitizer INSTANCE =
			new NonSanitizingMcpToolResultSanitizer();

	private NonSanitizingMcpToolResultSanitizer() {}

	@Override
	@NonNull
	public McpCompleteResult sanitize(@NonNull McpRequestContext requestContext,
			@NonNull String toolName,
			@NonNull McpJsonObject rawArguments,
			@NonNull McpCompleteResult completeResult) {
		requireNonNull(requestContext);
		requireNonNull(toolName);
		requireNonNull(rawArguments);
		return requireNonNull(completeResult);
	}
}
