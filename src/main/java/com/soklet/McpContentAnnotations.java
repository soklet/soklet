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
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable optional annotations for an MCP content block.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpContentAnnotations {
	@NonNull
	private final Set<@NonNull McpRole> audience;
	@Nullable
	private final Double priority;
	@Nullable
	private final Instant lastModified;

	/** @return an empty content-annotation builder */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	private McpContentAnnotations(@NonNull Builder builder) {
		this.audience = Collections.unmodifiableSet(
				new LinkedHashSet<>(builder.audience));
		this.priority = builder.priority;
		this.lastModified = builder.lastModified;
	}

	/** @return immutable intended audience */
	@NonNull
	public Set<@NonNull McpRole> getAudience() {
		return this.audience;
	}

	/** @return priority in the inclusive range {@code 0.0..1.0}, if set */
	@NonNull
	public Optional<@NonNull Double> getPriority() {
		return Optional.ofNullable(this.priority);
	}

	/** @return last-modified instant, if set */
	@NonNull
	public Optional<@NonNull Instant> getLastModified() {
		return Optional.ofNullable(this.lastModified);
	}

	/**
	 * Mutable builder for immutable {@link McpContentAnnotations}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final Set<@NonNull McpRole> audience = new LinkedHashSet<>();
		@Nullable
		private Double priority;
		@Nullable
		private Instant lastModified;

		private Builder() {
		}

		/**
		 * Replaces the intended audience.
		 *
		 * @param audience audience roles
		 * @return this builder
		 */
		@NonNull
		public Builder audience(@NonNull McpRole... audience) {
			requireNonNull(audience);
			this.audience.clear();
			for (McpRole role : audience)
				this.audience.add(requireNonNull(role));
			return this;
		}

		/**
		 * Sets a content priority.
		 *
		 * @param priority priority in the inclusive range {@code 0.0..1.0}
		 * @return this builder
		 * @throws NullPointerException if {@code priority} is null
		 * @throws IllegalArgumentException if the value is non-finite or outside
		 * the permitted range
		 */
		@NonNull
		public Builder priority(@NonNull Double priority) {
			requireNonNull(priority);
			if (!Double.isFinite(priority) || priority < 0.0 || priority > 1.0)
				throw new IllegalArgumentException(
						"Content priority must be finite and between 0.0 and 1.0.");
			this.priority = priority;
			return this;
		}

		/**
		 * Sets the last-modified time.
		 *
		 * @param lastModified last-modified time
		 * @return this builder
		 */
		@NonNull
		public Builder lastModified(@NonNull Instant lastModified) {
			this.lastModified = requireNonNull(lastModified);
			return this;
		}

		/** @return immutable content annotations */
		@NonNull
		public McpContentAnnotations build() {
			return new McpContentAnnotations(this);
		}
	}
}
