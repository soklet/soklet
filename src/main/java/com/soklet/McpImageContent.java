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
import java.util.Arrays;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable binary image MCP content block.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpImageContent implements McpContentBlock {
	private final byte @NonNull [] data;
	@NonNull
	private final String mimeType;
	@Nullable
	private final McpContentAnnotations annotations;
	@NonNull
	private final McpJsonObject metadata;

	/**
	 * Vends a builder primed with image bytes and their MIME type.
	 *
	 * @param data image bytes, defensively copied
	 * @param mimeType image MIME type
	 * @return image-content builder
	 */
	@NonNull
	public static Builder withDataAndMimeType(byte @NonNull [] data,
			@NonNull String mimeType) {
		return new Builder(data, mimeType);
	}

	private McpImageContent(@NonNull Builder builder) {
		this.data = Arrays.copyOf(builder.data, builder.data.length);
		this.mimeType = builder.mimeType;
		this.annotations = builder.annotations;
		this.metadata = builder.metadata;
	}

	/** @return a defensive copy of the image bytes */
	public byte @NonNull [] getData() {
		return Arrays.copyOf(this.data, this.data.length);
	}

	/** @return image MIME type */
	@NonNull
	public String getMimeType() {
		return this.mimeType;
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
	 * Mutable builder for immutable {@link McpImageContent}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		private final byte @NonNull [] data;
		@NonNull
		private final String mimeType;
		@Nullable
		private McpContentAnnotations annotations;
		@NonNull
		private McpJsonObject metadata = McpJsonObject.emptyInstance();

		private Builder(byte @NonNull [] data, @NonNull String mimeType) {
			this.data = Arrays.copyOf(requireNonNull(data), data.length);
			this.mimeType = requireNonNull(mimeType);
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

		/** @return immutable image content */
		@NonNull
		public McpImageContent build() {
			return new McpImageContent(this);
		}
	}
}
