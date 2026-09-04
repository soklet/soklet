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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable output of one completed MCP resource-read request.
 *
 * <p>The matching resource registration owns the fixed cache scope and
 * default time to live. An output may override only the time to live for this
 * response.
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
		return new Builder(resourceContents);
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

	private McpResourceOutput(@NonNull Builder builder) {
		this.contents = List.copyOf(builder.contents);
		this.cacheTimeToLiveOverride = builder.cacheTimeToLiveOverride;
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

	/**
	 * Mutable builder for immutable {@link McpResourceOutput}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final List<@NonNull McpResourceContents> contents =
				new ArrayList<>();
		@Nullable
		private Duration cacheTimeToLiveOverride;

		private Builder(@NonNull McpResourceContents resourceContents) {
			this.contents.add(requireNonNull(resourceContents));
		}

		/**
		 * Appends one resource-content value.
		 *
		 * @param resourceContents resource contents
		 * @return this builder
		 */
		@NonNull
		public Builder addContent(
				@NonNull McpResourceContents resourceContents) {
			this.contents.add(requireNonNull(resourceContents));
			return this;
		}

		/**
		 * Appends resource contents in iteration order.
		 *
		 * @param resourceContents resource contents
		 * @return this builder
		 */
		@NonNull
		public Builder addContents(
				@NonNull Collection<? extends @NonNull McpResourceContents>
						resourceContents) {
			requireNonNull(resourceContents);
			resourceContents.forEach(this::addContent);
			return this;
		}

		/**
		 * Overrides only the registration's default time to live.
		 *
		 * @param timeToLive nonnegative whole-millisecond duration
		 * @return this builder
		 * @throws IllegalArgumentException if the duration is negative or has
		 * sub-millisecond precision
		 */
		@NonNull
		public Builder cacheTimeToLiveOverride(@NonNull Duration timeToLive) {
			this.cacheTimeToLiveOverride =
					McpCachePolicy.requireTimeToLive(timeToLive);
			return this;
		}

		/** @return immutable resource output */
		@NonNull
		public McpResourceOutput build() {
			return new McpResourceOutput(this);
		}
	}
}
