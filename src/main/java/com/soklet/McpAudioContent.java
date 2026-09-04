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

import static com.soklet.McpContentValueSupport.annotationsEqual;
import static com.soklet.McpContentValueSupport.annotationsHashCode;
import static com.soklet.internal.mcp.protocol.McpApplicationMetadata.requireApplicationMetadata;
import static java.util.Objects.requireNonNull;

/**
 * Immutable binary audio MCP content block.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpAudioContent implements McpContentBlock {
	private final byte @NonNull [] data;
	@NonNull
	private final String mimeType;
	@Nullable
	private final McpContentAnnotations annotations;
	@NonNull
	private final McpJsonObject metadata;

	/**
	 * Vends a builder primed with audio bytes and their MIME type.
	 *
	 * @param data audio bytes, defensively copied
	 * @param mimeType audio MIME type
	 * @return audio-content builder
	 */
	@NonNull
	public static Builder withDataAndMimeType(byte @NonNull [] data,
			@NonNull String mimeType) {
		return new Builder(data, mimeType);
	}

	private McpAudioContent(@NonNull Builder builder) {
		this.data = Arrays.copyOf(builder.data, builder.data.length);
		this.mimeType = builder.mimeType;
		this.annotations = builder.annotations;
		this.metadata = requireApplicationMetadata(builder.metadata);
	}

	/** @return a defensive copy of the audio bytes */
	public byte @NonNull [] getData() {
		return Arrays.copyOf(this.data, this.data.length);
	}

	int dataLength() {
		return this.data.length;
	}

	/** @return audio MIME type */
	@NonNull
	public String getMimeType() {
		return this.mimeType;
	}

	/** @return content annotations, if supplied */
	@Override
	@NonNull
	public Optional<@NonNull McpContentAnnotations> getAnnotations() {
		return Optional.ofNullable(this.annotations);
	}

	/** @return immutable extension metadata */
	@Override
	@NonNull
	public McpJsonObject getMetadata() {
		return this.metadata;
	}

	/** @return whether every content property is structurally equal */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpAudioContent content))
			return false;
		return Arrays.equals(this.data, content.data)
				&& this.mimeType.equals(content.mimeType)
				&& annotationsEqual(this.annotations, content.annotations)
				&& this.metadata.equals(content.metadata);
	}

	/** @return structural content hash code */
	@Override
	public int hashCode() {
		int result = Arrays.hashCode(this.data);
		result = 31 * result + this.mimeType.hashCode();
		result = 31 * result + annotationsHashCode(this.annotations);
		result = 31 * result + this.metadata.hashCode();
		return result;
	}

	/**
	 * Mutable builder for immutable {@link McpAudioContent}.
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

		/** @return immutable audio content */
		@NonNull
		public McpAudioContent build() {
			return new McpAudioContent(this);
		}
	}
}
