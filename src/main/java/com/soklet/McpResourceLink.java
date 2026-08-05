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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable MCP content block linking to a resource.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpResourceLink implements McpContentBlock {
	@NonNull
	private final URI uri;
	@NonNull
	private final String name;
	@Nullable
	private final String title;
	@Nullable
	private final String description;
	@Nullable
	private final String mimeType;
	@NonNull
	private final List<@NonNull McpIcon> icons;
	@Nullable
	private final McpContentAnnotations annotations;
	@Nullable
	private final Long size;
	@NonNull
	private final McpJsonObject metadata;

	/**
	 * Vends a builder primed with the linked resource URI and name.
	 *
	 * @param uri absolute normalized resource URI in ASCII wire form
	 * @param name resource name
	 * @return resource-link builder
	 * @throws IllegalArgumentException if the URI is relative, not normalized,
	 * not in ASCII wire form, or the name is blank
	 */
	@NonNull
	public static Builder withUriAndName(@NonNull URI uri,
			@NonNull String name) {
		return new Builder(uri, name);
	}

	private McpResourceLink(@NonNull Builder builder) {
		this.uri = builder.uri;
		this.name = builder.name;
		this.title = builder.title;
		this.description = builder.description;
		this.mimeType = builder.mimeType;
		this.icons = List.copyOf(builder.icons);
		this.annotations = builder.annotations;
		this.size = builder.size;
		this.metadata = builder.metadata;
	}

	/** @return absolute normalized linked-resource URI in ASCII wire form */
	@NonNull
	public URI getUri() {
		return this.uri;
	}

	/** @return linked resource name */
	@NonNull
	public String getName() {
		return this.name;
	}

	/** @return human-readable title, if supplied */
	@NonNull
	public Optional<@NonNull String> getTitle() {
		return Optional.ofNullable(this.title);
	}

	/** @return human-readable description, if supplied */
	@NonNull
	public Optional<@NonNull String> getDescription() {
		return Optional.ofNullable(this.description);
	}

	/** @return resource MIME type, if supplied */
	@NonNull
	public Optional<@NonNull String> getMimeType() {
		return Optional.ofNullable(this.mimeType);
	}

	/** @return immutable icon list in registration order */
	@NonNull
	public List<@NonNull McpIcon> getIcons() {
		return this.icons;
	}

	/** @return content annotations, if supplied */
	@NonNull
	public Optional<@NonNull McpContentAnnotations> getAnnotations() {
		return Optional.ofNullable(this.annotations);
	}

	/** @return resource size in bytes, if supplied */
	@NonNull
	public Optional<@NonNull Long> getSize() {
		return Optional.ofNullable(this.size);
	}

	/** @return immutable extension metadata */
	@NonNull
	public McpJsonObject getMetadata() {
		return this.metadata;
	}

	/**
	 * Mutable builder for immutable {@link McpResourceLink}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final URI uri;
		@NonNull
		private final String name;
		@Nullable
		private String title;
		@Nullable
		private String description;
		@Nullable
		private String mimeType;
		@NonNull
		private final List<@NonNull McpIcon> icons = new ArrayList<>();
		@Nullable
		private McpContentAnnotations annotations;
		@Nullable
		private Long size;
		@NonNull
		private McpJsonObject metadata = McpJsonObject.emptyInstance();

		private Builder(@NonNull URI uri, @NonNull String name) {
			this.uri = McpResourceValueSupport
					.requireAbsoluteNormalizedUri(uri);
			this.name = McpResourceValueSupport.requireNonBlank(name,
					"MCP resource name");
		}

		/** @param title human-readable title
		 * @return this builder */
		@NonNull
		public Builder title(@NonNull String title) {
			this.title = requireNonNull(title);
			return this;
		}

		/** @param description human-readable description
		 * @return this builder */
		@NonNull
		public Builder description(@NonNull String description) {
			this.description = requireNonNull(description);
			return this;
		}

		/** @param mimeType resource MIME type
		 * @return this builder */
		@NonNull
		public Builder mimeType(@NonNull String mimeType) {
			this.mimeType = McpResourceValueSupport.requireNonBlank(mimeType,
					"MCP resource MIME type");
			return this;
		}

		/**
		 * Appends an icon.
		 *
		 * @param icon icon descriptor
		 * @return this builder
		 */
		@NonNull
		public Builder icon(@NonNull McpIcon icon) {
			this.icons.add(requireNonNull(icon));
			return this;
		}

		/** @param annotations content annotations
		 * @return this builder */
		@NonNull
		public Builder annotations(@NonNull McpContentAnnotations annotations) {
			this.annotations = requireNonNull(annotations);
			return this;
		}

		/**
		 * Sets the resource size in bytes.
		 *
		 * @param size nonnegative byte count
		 * @return this builder
		 * @throws IllegalArgumentException if {@code size} is negative
		 */
		@NonNull
		public Builder size(long size) {
			if (size < 0)
				throw new IllegalArgumentException(
						"Resource size must not be negative.");
			this.size = size;
			return this;
		}

		/** @param metadata protocol extension metadata
		 * @return this builder */
		@NonNull
		public Builder metadata(@NonNull McpJsonObject metadata) {
			this.metadata = requireNonNull(metadata);
			return this;
		}

		/** @return immutable resource link */
		@NonNull
		public McpResourceLink build() {
			return new McpResourceLink(this);
		}
	}
}
