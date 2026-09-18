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

import static com.soklet.McpContentValueSupport.resourceContentsEqual;
import static com.soklet.McpContentValueSupport.resourceContentsHashCode;
import static java.util.Objects.requireNonNull;

/**
 * Immutable output of one completed MCP resource-read request.
 *
 * <p>The matching resource registration owns the fixed cache scope and
 * default time to live. An output may override only the time to live for this
 * response. A localization-enabled server conservatively publishes a private,
 * zero-time-to-live policy regardless of an output override.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpResourceOutput implements McpCompletePayload {
	@NonNull
	private final List<@NonNull McpResourceContents> contents;
	@Nullable
	private final Duration cacheTimeToLiveOverride;

	/**
	 * Vends a builder containing its required first resource value.
	 *
	 * @param resourceContents resource contents
	 * @return resource-output builder
	 * @throws NullPointerException if {@code resourceContents} is null
	 */
	@NonNull
	public static Builder withContent(
			@NonNull McpResourceContents resourceContents) {
		return withContents(List.of(requireNonNull(resourceContents)));
	}

	/**
	 * Creates output containing exactly one resource value.
	 *
	 * @param resourceContents resource contents
	 * @return immutable resource output
	 * @throws NullPointerException if {@code resourceContents} is null
	 */
	@NonNull
	public static McpResourceOutput fromContent(
			@NonNull McpResourceContents resourceContents) {
		return withContent(resourceContents).build();
	}

	/**
	 * Vends a builder containing an ordered snapshot of resource contents.
	 *
	 * @param resourceContents nonempty resource contents in response order
	 * @return resource-output builder
	 * @throws NullPointerException if the list or any item is null
	 * @throws IllegalArgumentException if the list is empty
	 */
	@NonNull
	public static Builder withContents(
			@NonNull List<? extends @NonNull McpResourceContents> resourceContents) {
		return new Builder(resourceContents);
	}

	/**
	 * Creates output from an ordered snapshot of resource contents.
	 *
	 * @param resourceContents nonempty resource contents in response order
	 * @return immutable resource output
	 * @throws NullPointerException if the list or any item is null
	 * @throws IllegalArgumentException if the list is empty
	 */
	@NonNull
	public static McpResourceOutput fromContents(
			@NonNull List<? extends @NonNull McpResourceContents> resourceContents) {
		return withContents(resourceContents).build();
	}

	private McpResourceOutput(@NonNull Builder builder) {
		this.contents = requireContents(builder.contents);
		this.cacheTimeToLiveOverride = builder.cacheTimeToLiveOverride;
	}

	@NonNull
	private static List<@NonNull McpResourceContents> requireContents(
			@NonNull List<? extends @NonNull McpResourceContents> resourceContents) {
		List<McpResourceContents> copiedContents =
				List.copyOf(requireNonNull(resourceContents));
		if (copiedContents.isEmpty())
			throw new IllegalArgumentException(
					"At least one MCP resource-content value is required.");
		return copiedContents;
	}

	/** @return immutable resource contents in insertion order */
	@NonNull
	public List<@NonNull McpResourceContents> getContents() {
		return this.contents;
	}

	/**
	 * Returns this response's time-to-live override.
	 *
	 * @return override, or empty to use the registration default
	 */
	@NonNull
	public Optional<@NonNull Duration> getCacheTimeToLiveOverride() {
		return Optional.ofNullable(this.cacheTimeToLiveOverride);
	}

	/** @return whether contents and cache override are structurally equal */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpResourceOutput output))
			return false;
		return contentsEqual(this.contents, output.contents)
				&& Objects.equals(this.cacheTimeToLiveOverride,
					output.cacheTimeToLiveOverride);
	}

	/** @return structural resource-output hash code */
	@Override
	public int hashCode() {
		int contentsHashCode = 1;
		for (McpResourceContents resourceContents : this.contents)
			contentsHashCode = 31 * contentsHashCode
					+ resourceContentsHashCode(resourceContents);
		return Objects.hash(contentsHashCode, this.cacheTimeToLiveOverride);
	}

	private static boolean contentsEqual(
			@NonNull List<@NonNull McpResourceContents> first,
			@NonNull List<@NonNull McpResourceContents> second) {
		if (first.size() != second.size())
			return false;
		for (int index = 0; index < first.size(); ++index) {
			if (!resourceContentsEqual(first.get(index), second.get(index)))
				return false;
		}
		return true;
	}

	/**
	 * Mutable builder for immutable {@link McpResourceOutput}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private List<@NonNull McpResourceContents> contents;
		@Nullable
		private Duration cacheTimeToLiveOverride;

		private Builder(
				@NonNull List<? extends @NonNull McpResourceContents> resourceContents) {
			this.contents = requireContents(resourceContents);
		}

		/**
		 * Replaces all resource contents with an ordered immutable snapshot.
		 * A failed replacement leaves the previous contents unchanged.
		 *
		 * @param resourceContents nonempty resource contents in response order
		 * @return this builder
		 * @throws NullPointerException if the list or any item is null
		 * @throws IllegalArgumentException if the list is empty
		 */
		@NonNull
		public Builder contents(
				@NonNull List<? extends @NonNull McpResourceContents> resourceContents) {
			this.contents = requireContents(resourceContents);
			return this;
		}

		/**
		 * Overrides only the registration's default time to live.
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

		/** @return immutable resource output */
		@NonNull
		public McpResourceOutput build() {
			return new McpResourceOutput(this);
		}
	}
}
