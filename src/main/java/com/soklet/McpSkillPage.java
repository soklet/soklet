/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
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
 * One immutable complete {@code skills/list} page containing at most 32 skills.
 *
 * <p>Return this result directly, without a {@link McpCompleteResult} wrapper.
 * Registrations reference complete manifests; a skill is never split between
 * pages. Soklet derives descriptions and content identities from the registered
 * bundles rather than accepting replacement metadata or hashes from a handler.
 *
 * <p>The endpoint's Skills-list cache policy fixes scope and default freshness.
 * A page may override only the time to live, subject to the framework's
 * localization and security clamps. Result-level extension metadata does not
 * replace a bundle's frontmatter.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpSkillPage implements McpOperationResult {
	@NonNull
	private final List<@NonNull McpSkillRegistration> skillRegistrations;
	@NonNull
	private final McpJsonObject metadata;
	@Nullable
	private final String nextCursor;
	@Nullable
	private final Duration cacheTimeToLiveOverride;

	/** @return an empty Skills-page builder */
	@NonNull
	public static Builder builder() { return new Builder(); }

	private McpSkillPage(@NonNull Builder builder) {
		this.skillRegistrations = builder.skillRegistrations;
		this.metadata = builder.metadata;
		this.nextCursor = builder.nextCursor;
		this.cacheTimeToLiveOverride = builder.cacheTimeToLiveOverride;
	}

	/** @return immutable registration references in supplied order */
	@NonNull
	public List<@NonNull McpSkillRegistration> getSkillRegistrations() { return this.skillRegistrations; }

	/** @return immutable result-level protocol extension metadata */
	@NonNull
	public McpJsonObject getMetadata() { return this.metadata; }

	/**
	 * Returns the opaque cursor clients should supply for the next page.
	 * The application owns its integrity, authorization binding, expiry, page
	 * position, retained snapshot, content identities, and cross-node portability.
	 * A present empty string is preserved; Soklet does not mint or protect cursors.
	 *
	 * @return next cursor, or empty when this is the final page
	 */
	@NonNull
	public Optional<@NonNull String> getNextCursor() { return Optional.ofNullable(this.nextCursor); }

	/** @return freshness override, or empty to use the endpoint default */
	@NonNull
	public Optional<@NonNull Duration> getCacheTimeToLiveOverride() {
		return Optional.ofNullable(this.cacheTimeToLiveOverride);
	}

	/** @return whether every page property is structurally equal */
	@Override
	public boolean equals(@Nullable Object other) {
		return this == other || other instanceof McpSkillPage page
				&& this.skillRegistrations.equals(page.skillRegistrations)
				&& this.metadata.equals(page.metadata)
				&& Objects.equals(this.nextCursor, page.nextCursor)
				&& Objects.equals(this.cacheTimeToLiveOverride, page.cacheTimeToLiveOverride);
	}

	/** @return structural page hash code */
	@Override
	public int hashCode() {
		return Objects.hash(this.skillRegistrations, this.metadata, this.nextCursor, this.cacheTimeToLiveOverride);
	}

	/** @return diagnostic rendering without registrations, metadata, or cursor data */
	@Override
	@NonNull
	public String toString() { return "McpSkillPage[redacted]"; }

	/**
	 * Single-threaded builder for an immutable Skills page.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private List<@NonNull McpSkillRegistration> skillRegistrations = List.of();
		@NonNull
		private McpJsonObject metadata = McpJsonObject.emptyInstance();
		@Nullable
		private String nextCursor;
		@Nullable
		private Duration cacheTimeToLiveOverride;

		private Builder() {}

		/**
		 * Replaces registration references in supplied order. Null or empty clears
		 * the property. The complete list is snapshotted before replacing its prior
		 * value. Runtime validation requires exact configured registration instances,
		 * without duplicate URIs, and enforces current access and page-phase rules.
		 *
		 * @param skillRegistrations registration references, or null to clear
		 * @return this builder
		 * @throws NullPointerException if a list element is null
		 */
		@NonNull
		public Builder skillRegistrations(@Nullable List<@NonNull McpSkillRegistration> skillRegistrations) {
			this.skillRegistrations = skillRegistrations == null ? List.of() : List.copyOf(skillRegistrations);
			return this;
		}

		/**
		 * Sets result-level protocol extension metadata, not skill frontmatter.
		 *
		 * @param metadata immutable result-level protocol extension metadata
		 * @return this builder
		 * @throws NullPointerException if metadata is null
		 */
		@NonNull
		public Builder metadata(@NonNull McpJsonObject metadata) {
			this.metadata = requireNonNull(metadata);
			return this;
		}

		/**
		 * Supplies an application-owned opaque continuation cursor. Empty strings
		 * remain present values; no trimming or normalization occurs.
		 *
		 * @param nextCursor next cursor
		 * @return this builder
		 * @throws NullPointerException if the cursor is null
		 */
		@NonNull
		public Builder nextCursor(@NonNull String nextCursor) {
			this.nextCursor = requireNonNull(nextCursor);
			return this;
		}

		/**
		 * Overrides default freshness without changing cache scope or bypassing
		 * localization and security clamps.
		 *
		 * @param cacheTimeToLiveOverride nonnegative whole-millisecond duration
		 * @return this builder
		 * @throws NullPointerException if the duration is null
		 * @throws IllegalArgumentException if negative, sub-millisecond, or too
		 * large for a signed 64-bit millisecond value
		 */
		@NonNull
		public Builder cacheTimeToLiveOverride(@NonNull Duration cacheTimeToLiveOverride) {
			this.cacheTimeToLiveOverride = McpCachePolicy.requireTimeToLive(cacheTimeToLiveOverride);
			return this;
		}

		/**
		 * Builds one complete immutable page.
		 *
		 * @return immutable Skills page
		 * @throws IllegalStateException if the page contains more than 32 registrations
		 */
		@NonNull
		public McpSkillPage build() {
			if (this.skillRegistrations.size() > 32)
				throw new IllegalStateException("An MCP Skills page may contain at most 32 registrations.");
			return new McpSkillPage(this);
		}
	}
}
