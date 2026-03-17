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

import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static java.util.List.copyOf;
import static java.util.Objects.requireNonNull;

/**
 * Immutable tool result used by v1 MCP tool handlers.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Immutable
public final class McpToolResult {
	@NonNull
	private final List<@NonNull McpTextContent> content;
	@Nullable
	private final Object structuredContent;
	@NonNull
	private final Boolean error;

	private McpToolResult(@NonNull List<@NonNull McpTextContent> content,
												@Nullable Object structuredContent,
												@NonNull Boolean error) {
		requireNonNull(content);
		requireNonNull(error);

		this.content = copyOf(content);
		this.structuredContent = structuredContent;
		this.error = error;
	}

	/**
	 * Provides the text content blocks returned by the tool.
	 *
	 * @return the tool content blocks
	 */
	@NonNull
	public List<@NonNull McpTextContent> getContent() {
		return this.content;
	}

	/**
	 * Provides optional app-owned structured content to be marshaled into the MCP response.
	 *
	 * @return the structured content, if present
	 */
	@NonNull
	public Optional<Object> getStructuredContent() {
		return Optional.ofNullable(this.structuredContent);
	}

	/**
	 * Indicates whether the tool result represents an error result.
	 *
	 * @return {@code true} if the tool result is an error result
	 */
	@NonNull
	public Boolean isError() {
		return this.error;
	}

	/**
	 * Creates a builder for {@link McpToolResult}.
	 *
	 * @return a new builder
	 */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Creates an error result containing a single text message.
	 *
	 * @param message the error message
	 * @return an error tool result
	 */
	@NonNull
	public static McpToolResult fromErrorMessage(@NonNull String message) {
		requireNonNull(message);
		return builder()
				.isError(true)
				.content(McpTextContent.fromText(message))
				.build();
	}

	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;

		if (!(other instanceof McpToolResult mcpToolResult))
			return false;

		return getContent().equals(mcpToolResult.getContent())
				&& Objects.equals(this.structuredContent, mcpToolResult.structuredContent)
				&& isError().equals(mcpToolResult.isError());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getContent(), this.structuredContent, isError());
	}

	@Override
	public String toString() {
		return "McpToolResult{content=%s, structuredContent=%s, error=%s}".formatted(getContent(), this.structuredContent, isError());
	}

	/**
	 * Builder used to construct {@link McpToolResult} instances.
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final List<@NonNull McpTextContent> content;
		@Nullable
		private Object structuredContent;
		@NonNull
		private Boolean error;

		private Builder() {
			this.content = new ArrayList<>();
			this.structuredContent = null;
			this.error = false;
		}

		/**
		 * Appends a content block to the result.
		 *
		 * @param content the content block to append
		 * @return this builder
		 */
		@NonNull
		public Builder content(@NonNull McpTextContent content) {
			requireNonNull(content);
			this.content.add(content);
			return this;
		}

		/**
		 * Appends multiple content blocks to the result in order.
		 *
		 * @param content the content blocks to append
		 * @return this builder
		 */
		@NonNull
		public Builder content(@NonNull List<@NonNull McpTextContent> content) {
			requireNonNull(content);
			this.content.addAll(content);
			return this;
		}

		/**
		 * Sets the structured content value to be marshaled into the tool result.
		 *
		 * @param structuredContent the structured content value, possibly {@code null}
		 * @return this builder
		 */
		@NonNull
		public Builder structuredContent(@Nullable Object structuredContent) {
			this.structuredContent = structuredContent;
			return this;
		}

		/**
		 * Sets whether the result should be flagged as an error result.
		 *
		 * @param error the error flag
		 * @return this builder
		 */
		@NonNull
		public Builder isError(@NonNull Boolean error) {
			requireNonNull(error);
			this.error = error;
			return this;
		}

		/**
		 * Builds the immutable tool result.
		 *
		 * @return the built tool result
		 */
		@NonNull
		public McpToolResult build() {
			return new McpToolResult(this.content, this.structuredContent, this.error);
		}
	}
}
