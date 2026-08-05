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
import java.net.URI;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable UTF-16 Java text contents for one MCP resource.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpTextResourceContents implements McpResourceContents {
	@NonNull
	private final URI uri;
	@NonNull
	private final String text;
	@Nullable
	private final String mimeType;
	@NonNull
	private final McpJsonObject metadata;

	/**
	 * Vends a builder primed with the resource URI and text.
	 *
	 * @param uri resource URI
	 * @param text resource text
	 * @return resource-content builder
	 */
	@NonNull
	public static Builder withUriAndText(@NonNull URI uri,
			@NonNull String text) {
		return new Builder(uri, text);
	}

	private McpTextResourceContents(@NonNull Builder builder) {
		this.uri = builder.uri;
		this.text = builder.text;
		this.mimeType = builder.mimeType;
		this.metadata = builder.metadata;
	}

	/**
	 * Returns the URI that identifies this resource.
	 *
	 * @return resource URI
	 */
	@Override
	@NonNull
	public URI getUri() {
		return this.uri;
	}

	/** @return resource text */
	@NonNull
	public String getText() {
		return this.text;
	}

	/**
	 * Returns the resource MIME type, if one was supplied.
	 *
	 * @return resource MIME type, if available
	 */
	@Override
	@NonNull
	public Optional<@NonNull String> getMimeType() {
		return Optional.ofNullable(this.mimeType);
	}

	/**
	 * Returns protocol extension metadata associated with this resource.
	 *
	 * @return immutable metadata object
	 */
	@Override
	@NonNull
	public McpJsonObject getMetadata() {
		return this.metadata;
	}

	/**
	 * Mutable builder for immutable {@link McpTextResourceContents}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final URI uri;
		@NonNull
		private final String text;
		@Nullable
		private String mimeType;
		@NonNull
		private McpJsonObject metadata = McpJsonObject.emptyInstance();

		private Builder(@NonNull URI uri, @NonNull String text) {
			this.uri = requireNonNull(uri);
			this.text = requireNonNull(text);
		}

		/**
		 * Sets the resource MIME type.
		 *
		 * @param mimeType MIME type
		 * @return this builder
		 */
		@NonNull
		public Builder mimeType(@NonNull String mimeType) {
			this.mimeType = requireNonNull(mimeType);
			return this;
		}

		/**
		 * Sets protocol extension metadata.
		 *
		 * @param metadata immutable metadata object
		 * @return this builder
		 */
		@NonNull
		public Builder metadata(@NonNull McpJsonObject metadata) {
			this.metadata = requireNonNull(metadata);
			return this;
		}

		/** @return immutable text resource contents */
		@NonNull
		public McpTextResourceContents build() {
			return new McpTextResourceContents(this);
		}
	}
}
