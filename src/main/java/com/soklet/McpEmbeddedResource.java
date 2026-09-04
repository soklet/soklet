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

import static com.soklet.McpContentValueSupport.annotationsEqual;
import static com.soklet.McpContentValueSupport.annotationsHashCode;
import static com.soklet.McpContentValueSupport.resourceContentsEqual;
import static com.soklet.McpContentValueSupport.resourceContentsHashCode;
import static com.soklet.internal.mcp.protocol.McpApplicationMetadata.requireApplicationMetadata;
import static java.util.Objects.requireNonNull;

/**
 * Immutable embedded-resource MCP content block.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpEmbeddedResource implements McpContentBlock {
	@NonNull
	private final McpResourceContents resource;
	@Nullable
	private final McpContentAnnotations annotations;
	@NonNull
	private final McpJsonObject metadata;

	/**
	 * Vends a builder primed with resource contents.
	 *
	 * @param resource embedded resource contents
	 * @return embedded-resource builder
	 */
	@NonNull
	public static Builder withResource(@NonNull McpResourceContents resource) {
		return new Builder(resource);
	}

	private McpEmbeddedResource(@NonNull Builder builder) {
		this.resource = builder.resource;
		this.annotations = builder.annotations;
		this.metadata = requireApplicationMetadata(builder.metadata);
	}

	/** @return embedded resource contents */
	@NonNull
	public McpResourceContents getResource() {
		return this.resource;
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
		if (!(other instanceof McpEmbeddedResource content))
			return false;
		return resourceContentsEqual(this.resource, content.resource)
				&& annotationsEqual(this.annotations, content.annotations)
				&& this.metadata.equals(content.metadata);
	}

	/** @return structural content hash code */
	@Override
	public int hashCode() {
		int result = resourceContentsHashCode(this.resource);
		result = 31 * result + annotationsHashCode(this.annotations);
		result = 31 * result + this.metadata.hashCode();
		return result;
	}

	/**
	 * Mutable builder for immutable {@link McpEmbeddedResource}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final McpResourceContents resource;
		@Nullable
		private McpContentAnnotations annotations;
		@NonNull
		private McpJsonObject metadata = McpJsonObject.emptyInstance();

		private Builder(@NonNull McpResourceContents resource) {
			this.resource = requireNonNull(resource);
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

		/** @return immutable embedded-resource content */
		@NonNull
		public McpEmbeddedResource build() {
			return new McpEmbeddedResource(this);
		}
	}
}
