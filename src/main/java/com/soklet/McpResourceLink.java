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
import java.util.Objects;
import java.util.Optional;

import static com.soklet.McpContentValueSupport.annotationsEqual;
import static com.soklet.McpContentValueSupport.annotationsHashCode;
import static com.soklet.McpContentValueSupport.iconListHashCode;
import static com.soklet.McpContentValueSupport.iconListsEqual;
import static com.soklet.internal.mcp.protocol.McpApplicationMetadata.requireApplicationMetadata;
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
	private final Long sizeInBytes;
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

	/**
	 * Creates a resource-link content block from an existing concrete resource
	 * descriptor. Every descriptor property is preserved.
	 *
	 * @param resourceDescriptor resource descriptor to copy
	 * @return immutable resource link
	 * @throws NullPointerException if {@code resourceDescriptor} is null
	 */
	@NonNull
	public static McpResourceLink fromResourceDescriptor(
			@NonNull McpResourceDescriptor resourceDescriptor) {
		return new McpResourceLink(resourceDescriptor);
	}

	private McpResourceLink(
			@NonNull McpResourceDescriptor resourceDescriptor) {
		McpResourceDescriptor descriptor = requireNonNull(resourceDescriptor);
		this.uri = descriptor.getUri();
		this.name = descriptor.getName();
		this.title = descriptor.getTitle().orElse(null);
		this.description = descriptor.getDescription().orElse(null);
		this.mimeType = descriptor.getMimeType().orElse(null);
		this.icons = List.copyOf(descriptor.getIcons());
		this.annotations = descriptor.getAnnotations().orElse(null);
		this.sizeInBytes = descriptor.getSizeInBytes().orElse(null);
		this.metadata = descriptor.getMetadata();
	}

	private McpResourceLink(@NonNull Builder builder) {
		this.uri = builder.uri;
		this.name = builder.name;
		this.title = builder.title;
		this.description = builder.description;
		this.mimeType = builder.mimeType;
		this.icons = List.copyOf(builder.icons);
		this.annotations = builder.annotations;
		this.sizeInBytes = builder.sizeInBytes;
		this.metadata = requireApplicationMetadata(builder.metadata);
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
	@Override
	@NonNull
	public Optional<@NonNull McpContentAnnotations> getAnnotations() {
		return Optional.ofNullable(this.annotations);
	}

	/** @return resource size in bytes, if supplied */
	@NonNull
	public Optional<@NonNull Long> getSizeInBytes() {
		return Optional.ofNullable(this.sizeInBytes);
	}

	/** @return immutable extension metadata */
	@Override
	@NonNull
	public McpJsonObject getMetadata() {
		return this.metadata;
	}

	/** @return whether every linked-resource property is structurally equal */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpResourceLink link))
			return false;
		return this.uri.equals(link.uri)
				&& this.name.equals(link.name)
				&& Objects.equals(this.title, link.title)
				&& Objects.equals(this.description, link.description)
				&& Objects.equals(this.mimeType, link.mimeType)
				&& iconListsEqual(this.icons, link.icons)
				&& annotationsEqual(this.annotations, link.annotations)
				&& Objects.equals(this.sizeInBytes, link.sizeInBytes)
				&& this.metadata.equals(link.metadata);
	}

	/** @return structural linked-resource hash code */
	@Override
	public int hashCode() {
		int result = Objects.hash(this.uri, this.name, this.title,
				this.description, this.mimeType);
		result = 31 * result + iconListHashCode(this.icons);
		result = 31 * result + annotationsHashCode(this.annotations);
		result = 31 * result + Objects.hashCode(this.sizeInBytes);
		result = 31 * result + this.metadata.hashCode();
		return result;
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
		private Long sizeInBytes;
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
		public Builder addIcon(@NonNull McpIcon icon) {
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
		 * @param sizeInBytes nonnegative byte count
		 * @return this builder
		 * @throws NullPointerException if {@code sizeInBytes} is null
		 * @throws IllegalArgumentException if {@code sizeInBytes} is negative
		 */
		@NonNull
		public Builder sizeInBytes(@NonNull Long sizeInBytes) {
			requireNonNull(sizeInBytes);
			if (sizeInBytes < 0)
				throw new IllegalArgumentException(
						"Resource size must not be negative.");
			this.sizeInBytes = sizeInBytes;
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
