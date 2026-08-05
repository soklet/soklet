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
import java.util.Arrays;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable binary contents for one MCP resource.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpBlobResourceContents implements McpResourceContents {
	@NonNull
	private final URI uri;
	private final byte @NonNull [] data;
	@Nullable
	private final String mimeType;
	@NonNull
	private final McpJsonObject metadata;

	/**
	 * Vends a builder primed with the resource URI and binary data.
	 *
	 * @param uri absolute normalized resource URI in ASCII wire form
	 * @param data resource bytes, defensively copied
	 * @return resource-content builder
	 * @throws IllegalArgumentException if the URI is relative, not normalized,
	 * or not in ASCII wire form
	 */
	@NonNull
	public static Builder withUriAndData(@NonNull URI uri,
			byte @NonNull [] data) {
		return new Builder(uri, data);
	}

	private McpBlobResourceContents(@NonNull Builder builder) {
		this.uri = builder.uri;
		this.data = Arrays.copyOf(builder.data, builder.data.length);
		this.mimeType = builder.mimeType;
		this.metadata = builder.metadata;
	}

	/**
	 * Returns the URI that identifies this resource.
	 *
	 * @return absolute normalized resource URI in ASCII wire form
	 */
	@Override
	@NonNull
	public URI getUri() {
		return this.uri;
	}

	/**
	 * Returns a defensive copy of the resource bytes.
	 *
	 * @return resource bytes
	 */
	public byte @NonNull [] getData() {
		return Arrays.copyOf(this.data, this.data.length);
	}

	int dataLength() {
		return this.data.length;
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
	 * Mutable builder for immutable {@link McpBlobResourceContents}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final URI uri;
		private final byte @NonNull [] data;
		@Nullable
		private String mimeType;
		@NonNull
		private McpJsonObject metadata = McpJsonObject.emptyInstance();

		private Builder(@NonNull URI uri, byte @NonNull [] data) {
			this.uri = McpResourceValueSupport
					.requireAbsoluteNormalizedUri(uri);
			this.data = Arrays.copyOf(requireNonNull(data), data.length);
		}

		/**
		 * Sets the resource MIME type.
		 *
		 * @param mimeType MIME type
		 * @return this builder
		 */
		@NonNull
		public Builder mimeType(@NonNull String mimeType) {
			this.mimeType = McpResourceValueSupport.requireNonBlank(mimeType,
					"MCP resource MIME type");
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

		/** @return immutable binary resource contents */
		@NonNull
		public McpBlobResourceContents build() {
			return new McpBlobResourceContents(this);
		}
	}
}
