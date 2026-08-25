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
import java.util.Optional;

import static com.soklet.internal.mcp.protocol.McpApplicationMetadata.requireApplicationMetadata;
import static java.util.Objects.requireNonNull;

/**
 * Immutable textual MCP content block.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpTextContent implements McpContentBlock {
	@NonNull
	private final String text;
	@Nullable
	private final McpContentAnnotations annotations;
	@NonNull
	private final McpJsonObject metadata;

	/**
	 * Creates unannotated text content.
	 *
	 * @param text text value
	 * @return immutable text content
	 */
	@NonNull
	public static McpTextContent fromText(@NonNull String text) {
		return withText(text).build();
	}

	/**
	 * Vends a builder primed with the text value.
	 *
	 * @param text text value
	 * @return text-content builder
	 */
	@NonNull
	public static Builder withText(@NonNull String text) {
		return new Builder(text);
	}

	private McpTextContent(@NonNull Builder builder) {
		this.text = builder.text;
		this.annotations = builder.annotations;
		this.metadata = requireApplicationMetadata(builder.metadata);
	}

	/** @return text value */
	@NonNull
	public String getText() {
		return this.text;
	}

	/** @return content annotations, if supplied */
	@NonNull
	public Optional<@NonNull McpContentAnnotations> getAnnotations() {
		return Optional.ofNullable(this.annotations);
	}

	/** @return immutable extension metadata */
	@NonNull
	public McpJsonObject getMetadata() {
		return this.metadata;
	}

	/**
	 * Mutable builder for immutable {@link McpTextContent}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final String text;
		@Nullable
		private McpContentAnnotations annotations;
		@NonNull
		private McpJsonObject metadata = McpJsonObject.emptyInstance();

		private Builder(@NonNull String text) {
			this.text = requireNonNull(text);
		}

		/** @param annotations content annotations
		 * @return this builder */
		@NonNull
		public Builder annotations(@NonNull McpContentAnnotations annotations) {
			this.annotations = requireNonNull(annotations);
			return this;
		}

		/** @param metadata protocol extension metadata
		 * @return this builder */
		@NonNull
		public Builder metadata(@NonNull McpJsonObject metadata) {
			this.metadata = requireNonNull(metadata);
			return this;
		}

		/** @return immutable text content */
		@NonNull
		public McpTextContent build() {
			return new McpTextContent(this);
		}
	}
}
