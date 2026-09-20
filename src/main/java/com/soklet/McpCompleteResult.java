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

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

import java.util.Objects;

import static com.soklet.internal.mcp.protocol.McpApplicationMetadata.requireApplicationMetadata;
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
	 * @param toolOutput tool output
	 * @return complete tool result
	 */
	@NonNull
	public static McpCompleteResult fromToolOutput(
			@NonNull McpToolOutput toolOutput) {
		return new McpCompleteResult(toolOutput, McpJsonObject.emptyInstance());
	}

	/**
	 * Wraps explicit prompt output.
	 *
	 * @param promptOutput prompt output
	 * @return complete prompt result
	 */
	@NonNull
	public static McpCompleteResult fromPromptOutput(
			@NonNull McpPromptOutput promptOutput) {
		return new McpCompleteResult(promptOutput, McpJsonObject.emptyInstance());
	}

	/**
	 * Wraps explicit resource output.
	 *
	 * @param resourceOutput resource output
	 * @return complete resource result
	 */
	@NonNull
	public static McpCompleteResult fromResourceOutput(
			@NonNull McpResourceOutput resourceOutput) {
		return new McpCompleteResult(resourceOutput, McpJsonObject.emptyInstance());
	}

	/**
	 * Starts a builder with tool output and empty result metadata.
	 *
	 * @param toolOutput complete tool output
	 * @return mutable result builder
	 */
	@NonNull
	public static Builder withToolOutput(@NonNull McpToolOutput toolOutput) {
		return new Builder(toolOutput);
	}

	/**
	 * Starts a builder with prompt output and empty result metadata.
	 *
	 * @param promptOutput complete prompt output
	 * @return mutable result builder
	 */
	@NonNull
	public static Builder withPromptOutput(@NonNull McpPromptOutput promptOutput) {
		return new Builder(promptOutput);
	}

	/**
	 * Starts a builder with resource output and empty result metadata.
	 *
	 * @param resourceOutput complete resource output
	 * @return mutable result builder
	 */
	@NonNull
	public static Builder withResourceOutput(@NonNull McpResourceOutput resourceOutput) {
		return new Builder(resourceOutput);
	}

	private McpCompleteResult(@NonNull McpCompletePayload payload,
			@NonNull McpJsonObject metadata) {
		this.payload = requireNonNull(payload);
		this.metadata = requireApplicationMetadata(metadata);
	}

	/**
	 * Starts an independent builder preserving every field of this result.
	 *
	 * @return mutable result builder with the same payload and metadata
	 */
	@NonNull
	public Builder toBuilder() {
		return new Builder(this.payload).metadata(this.metadata);
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

	/** @return whether the payload and metadata are structurally equal */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpCompleteResult result))
			return false;
		return this.payload.equals(result.payload)
				&& this.metadata.equals(result.metadata);
	}

	/** @return structural payload-and-metadata hash code */
	@Override
	public int hashCode() {
		return Objects.hash(this.payload, this.metadata);
	}

	/**
	 * Mutable builder for an immutable complete result.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private McpCompletePayload payload;
		@NonNull
		private McpJsonObject metadata = McpJsonObject.emptyInstance();

		private Builder(@NonNull McpCompletePayload payload) {
			this.payload = requireNonNull(payload);
		}

		/**
		 * Replaces the operation-specific payload.
		 *
		 * @param payload complete tool, prompt, or resource output
		 * @return this builder
		 */
		@NonNull
		public Builder payload(@NonNull McpCompletePayload payload) {
			this.payload = requireNonNull(payload);
			return this;
		}

		/**
		 * Replaces application-owned result metadata.
		 * Metadata is out-of-band data, not a confidential channel: hosts may log
		 * or forward it. Application authorization and redaction remain necessary.
		 *
		 * @param metadata immutable metadata without reserved MCP keys
		 * @return this builder
		 * @throws NullPointerException if {@code metadata} is null
		 * @throws IllegalArgumentException if metadata uses a reserved MCP key
		 */
		@NonNull
		public Builder metadata(@NonNull McpJsonObject metadata) {
			this.metadata = requireApplicationMetadata(metadata);
			return this;
		}

		/** @return immutable snapshot of the current payload and metadata */
		@NonNull
		public McpCompleteResult build() {
			return new McpCompleteResult(this.payload, this.metadata);
		}
	}
}
