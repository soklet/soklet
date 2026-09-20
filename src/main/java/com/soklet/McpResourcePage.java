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
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * One immutable complete {@code resources/list} page.
 *
 * <p>The endpoint's resources-list cache policy owns the fixed cache scope and
 * default time to live. A page may override only the time to live. The page is
 * returned directly and does not require a {@link McpCompleteResult} wrapper.
 * A localization-enabled server conservatively publishes a private,
 * zero-time-to-live policy regardless of a page override.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpResourcePage implements McpOperationResult {
	@NonNull
	private final List<@NonNull McpResourceDescriptor> resourceDescriptors;
	@NonNull
	private final McpJsonObject metadata;
	@Nullable
	private final String nextCursor;
	@Nullable
	private final Duration cacheTimeToLiveOverride;

	/** @return an empty resource-page builder */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	private McpResourcePage(@NonNull Builder builder) {
		this.resourceDescriptors = List.copyOf(builder.resourceDescriptors);
		this.metadata = builder.metadata;
		this.nextCursor = builder.nextCursor;
		this.cacheTimeToLiveOverride = builder.cacheTimeToLiveOverride;
	}

	/** @return immutable resource descriptors in insertion order */
	@NonNull
	public List<@NonNull McpResourceDescriptor> getResourceDescriptors() {
		return this.resourceDescriptors;
	}

	/** @return immutable result-level protocol extension metadata */
	@NonNull
	public McpJsonObject getMetadata() {
		return this.metadata;
	}

	/**
	 * Returns the opaque cursor clients should supply for the next page.
	 *
	 * <p>The application is responsible for binding a cursor to the intended
	 * page position, retained snapshot and catalog revision, authorization
	 * context, and expiry as required by its deployment. Soklet does not sign,
	 * encrypt, store, or verify the cursor.
	 *
	 * @return next cursor, or empty when this is the final page
	 */
	@NonNull
	public Optional<@NonNull String> getNextCursor() {
		return Optional.ofNullable(this.nextCursor);
	}

	/**
	 * Returns this page's time-to-live override.
	 *
	 * @return override, or empty to use the endpoint default
	 */
	@NonNull
	public Optional<@NonNull Duration> getCacheTimeToLiveOverride() {
		return Optional.ofNullable(this.cacheTimeToLiveOverride);
	}

	/** @return whether every page property is structurally equal */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpResourcePage page))
			return false;
		return this.resourceDescriptors.equals(page.resourceDescriptors)
				&& this.metadata.equals(page.metadata)
				&& Objects.equals(this.nextCursor, page.nextCursor)
				&& Objects.equals(this.cacheTimeToLiveOverride,
						page.cacheTimeToLiveOverride);
	}

	/** @return structural page hash code */
	@Override
	public int hashCode() {
		return Objects.hash(this.resourceDescriptors, this.metadata, this.nextCursor,
				this.cacheTimeToLiveOverride);
	}

	/**
	 * Mutable builder for an immutable resource page.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private List<@NonNull McpResourceDescriptor> resourceDescriptors =
				List.of();
		@NonNull
		private McpJsonObject metadata = McpJsonObject.emptyInstance();
		@Nullable
		private String nextCursor;
		@Nullable
		private Duration cacheTimeToLiveOverride;

		private Builder() {
		}

		/**
		 * Replaces resource descriptors in supplied order.
		 * Null or empty clears the property. The complete list is validated and
		 * snapshotted before replacing the prior value.
		 *
		 * @param resourceDescriptors resource descriptors, or null to clear
		 * @return this builder
		 * @throws NullPointerException if a list element is null
		 */
		@NonNull
		public Builder resourceDescriptors(
				@Nullable List<@NonNull McpResourceDescriptor> resourceDescriptors) {
			this.resourceDescriptors = resourceDescriptors == null ? List.of()
					: List.copyOf(resourceDescriptors);
			return this;
		}

		/** @param metadata result-level protocol extension metadata
		 * @return this builder */
		@NonNull
		public Builder metadata(@NonNull McpJsonObject metadata) {
			this.metadata = requireNonNull(metadata);
			return this;
		}

		/**
		 * Supplies the opaque cursor clients should use for the next page.
		 *
		 * <p>The application owns the cursor's integrity, confidentiality,
		 * authorization binding, expiry, page position, retained snapshot,
		 * catalog revision, and cross-instance portability. An empty string
		 * remains a present protocol value. Soklet enforces its configured
		 * outgoing UTF-8 bound but does not mint or protect the value.
		 *
		 * @param nextCursor next cursor
		 * @return this builder
		 */
		@NonNull
		public Builder nextCursor(@NonNull String nextCursor) {
			this.nextCursor = requireNonNull(nextCursor);
			return this;
		}

		/**
		 * Overrides only the endpoint's default time to live for this page.
		 *
		 * @param cacheTimeToLiveOverride nonnegative whole-millisecond duration
		 * @return this builder
		 * @throws IllegalArgumentException if the duration is negative or has
		 * sub-millisecond precision
		 */
		@NonNull
		public Builder cacheTimeToLiveOverride(
				@NonNull Duration cacheTimeToLiveOverride) {
			this.cacheTimeToLiveOverride =
					McpCachePolicy.requireTimeToLive(cacheTimeToLiveOverride);
			return this;
		}

		/** @return immutable resource page */
		@NonNull
		public McpResourcePage build() {
			return new McpResourcePage(this);
		}
	}
}
