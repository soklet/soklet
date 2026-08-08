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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable output of one completed MCP tool call.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpToolOutput implements McpCompletePayload {
	@NonNull
	private final List<@NonNull McpContentBlock> content;
	@Nullable
	private final McpJsonValue structuredContent;
	private final boolean error;

	/** @return an empty tool-output builder */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Creates successful prose output.
	 *
	 * @param text prose text
	 * @return tool output containing one text block
	 */
	@NonNull
	public static McpToolOutput fromText(@NonNull String text) {
		return builder().content(McpTextContent.fromText(text)).build();
	}

	/**
	 * Creates successful structured output.
	 *
	 * @param structuredContent structured JSON value
	 * @return tool output carrying structured content
	 */
	@NonNull
	public static McpToolOutput fromStructuredContent(
			@NonNull McpJsonValue structuredContent) {
		return builder().structuredContent(structuredContent).build();
	}

	/**
	 * Creates error prose output.
	 *
	 * @param text safe client-visible error text
	 * @return error tool output containing one text block
	 */
	@NonNull
	public static McpToolOutput fromErrorText(@NonNull String text) {
		return builder().content(McpTextContent.fromText(text))
				.isError(true).build();
	}

	private McpToolOutput(@NonNull Builder builder) {
		this.content = List.copyOf(builder.content);
		this.structuredContent = builder.structuredContent;
		this.error = builder.error;
	}

	/** @return immutable content blocks in insertion order */
	@NonNull
	public List<@NonNull McpContentBlock> getContent() {
		return this.content;
	}

	/** @return structured JSON content, if supplied */
	@NonNull
	public Optional<@NonNull McpJsonValue> getStructuredContent() {
		return Optional.ofNullable(this.structuredContent);
	}

	/** @return whether the tool reported an application-level error */
	@NonNull
	public Boolean isError() {
		return this.error;
	}

	/**
	 * Mutable builder for immutable {@link McpToolOutput}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final List<@NonNull McpContentBlock> content = new ArrayList<>();
		@Nullable
		private McpJsonValue structuredContent;
		private boolean error;

		private Builder() {
		}

		/**
		 * Appends one content block.
		 *
		 * @param content content block
		 * @return this builder
		 */
		@NonNull
		public Builder content(@NonNull McpContentBlock content) {
			this.content.add(requireNonNull(content));
			return this;
		}

		/**
		 * Appends content blocks in iteration order.
		 *
		 * @param content content blocks
		 * @return this builder
		 */
		@NonNull
		public Builder content(
				@NonNull Collection<? extends @NonNull McpContentBlock> content) {
			requireNonNull(content);
			content.forEach(this::content);
			return this;
		}

		/**
		 * Sets structured JSON content.
		 *
		 * @param structuredContent structured JSON value
		 * @return this builder
		 */
		@NonNull
		public Builder structuredContent(
				@NonNull McpJsonValue structuredContent) {
			this.structuredContent = requireNonNull(structuredContent);
			return this;
		}

		/**
		 * Sets whether the tool result is an application-level error.
		 *
		 * @param error error state
		 * @return this builder
		 * @throws NullPointerException if {@code error} is null
		 */
		@NonNull
		public Builder isError(@NonNull Boolean error) {
			this.error = requireNonNull(error);
			return this;
		}

		/** @return immutable tool output */
		@NonNull
		public McpToolOutput build() {
			return new McpToolOutput(this);
		}
	}
}
