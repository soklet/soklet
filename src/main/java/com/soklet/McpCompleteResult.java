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
 * Immutable completed result returned by an MCP operation handler.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpCompleteResult implements McpOperationResult {
	@NonNull
	private final McpCompletePayload payload;
	@NonNull
	private final McpJsonObject metadata;

	/**
	 * Creates a successful prose tool result.
	 *
	 * @param text prose text
	 * @return complete tool result
	 */
	@NonNull
	public static McpCompleteResult fromToolText(@NonNull String text) {
		return fromToolOutput(McpToolOutput.fromText(text));
	}

	/**
	 * Creates a successful structured tool result.
	 *
	 * @param structuredContent structured JSON value
	 * @return complete tool result
	 */
	@NonNull
	public static McpCompleteResult fromToolStructuredContent(
			@NonNull McpJsonValue structuredContent) {
		return fromToolOutput(
				McpToolOutput.fromStructuredContent(structuredContent));
	}

	/**
	 * Creates an application-level error tool result.
	 *
	 * @param text safe client-visible error text
	 * @return complete error tool result
	 */
	@NonNull
	public static McpCompleteResult fromToolErrorText(@NonNull String text) {
		return fromToolOutput(McpToolOutput.fromErrorText(text));
	}

	/**
	 * Wraps explicit tool output.
	 *
	 * @param output tool output
	 * @return complete tool result
	 */
	@NonNull
	public static McpCompleteResult fromToolOutput(
			@NonNull McpToolOutput output) {
		return new McpCompleteResult(output, McpJsonObject.emptyInstance());
	}

	/**
	 * Wraps explicit prompt output.
	 *
	 * @param output prompt output
	 * @return complete prompt result
	 */
	@NonNull
	public static McpCompleteResult fromPromptOutput(
			@NonNull McpPromptOutput output) {
		return new McpCompleteResult(output, McpJsonObject.emptyInstance());
	}

	/**
	 * Wraps explicit resource output.
	 *
	 * @param output resource output
	 * @return complete resource result
	 */
	@NonNull
	public static McpCompleteResult fromResourceOutput(
			@NonNull McpResourceOutput output) {
		return new McpCompleteResult(output, McpJsonObject.emptyInstance());
	}

	private McpCompleteResult(@NonNull McpCompletePayload payload,
			@NonNull McpJsonObject metadata) {
		this.payload = requireNonNull(payload);
		this.metadata = requireNonNull(metadata);
	}

	/**
	 * Returns a copy carrying the supplied protocol extension metadata.
	 *
	 * @param metadata immutable metadata object
	 * @return copied complete result
	 */
	@NonNull
	public McpCompleteResult withMetadata(@NonNull McpJsonObject metadata) {
		return new McpCompleteResult(this.payload, metadata);
	}

	/** @return operation-specific complete payload */
	@NonNull
	public McpCompletePayload getPayload() {
		return this.payload;
	}

	/** @return immutable protocol extension metadata */
	@NonNull
	public McpJsonObject getMetadata() {
		return this.metadata;
	}
}
