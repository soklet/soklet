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

	/** @return an empty resource-output builder */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	private McpResourceOutput(@NonNull Builder builder) {
		if (builder.contents.isEmpty())
			throw new IllegalStateException(
					"A successful MCP resource output must contain at least one value.");
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

		private Builder() {
		}

		/** @param content resource contents
		 * @return this builder */
		@NonNull
		public Builder content(@NonNull McpResourceContents content) {
			this.contents.add(requireNonNull(content));
			return this;
		}

		/**
		 * Appends resource contents in iteration order.
		 *
		 * @param contents resource contents
		 * @return this builder
		 */
		@NonNull
		public Builder contents(
				@NonNull Collection<? extends @NonNull McpResourceContents> contents) {
			requireNonNull(contents);
			contents.forEach(this::content);
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
			requireNonNull(timeToLive);
			if (timeToLive.isNegative()
					|| timeToLive.getNano() % 1_000_000 != 0)
				throw new IllegalArgumentException(
						"Cache time to live must be a nonnegative whole-millisecond duration.");
			this.cacheTimeToLiveOverride = timeToLive;
			return this;
		}

		/** @return immutable resource output */
		@NonNull
		public McpResourceOutput build() {
			return new McpResourceOutput(this);
		}
	}
}
