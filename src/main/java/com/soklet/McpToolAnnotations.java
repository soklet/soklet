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

import static java.util.Objects.requireNonNull;

/**
 * Immutable MCP client hints describing a tool's behavior.
 *
 * <p>Hints are advisory and must never be used as authorization or safety
 * controls. Each boolean preserves the difference between an omitted hint and
 * an explicitly supplied {@code false} value.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpToolAnnotations {
	@Nullable
	private final String title;
	@Nullable
	private final Boolean readOnlyHint;
	@Nullable
	private final Boolean destructiveHint;
	@Nullable
	private final Boolean idempotentHint;
	@Nullable
	private final Boolean openWorldHint;

	/** @return an empty tool-annotation builder */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	private McpToolAnnotations(@NonNull Builder builder) {
		this.title = builder.title;
		this.readOnlyHint = builder.readOnlyHint;
		this.destructiveHint = builder.destructiveHint;
		this.idempotentHint = builder.idempotentHint;
		this.openWorldHint = builder.openWorldHint;
	}

	/** @return human-readable title hint, if supplied */
	@NonNull
	public Optional<@NonNull String> getTitle() {
		return Optional.ofNullable(this.title);
	}

	/** @return read-only hint, preserving omission */
	@NonNull
	public Optional<@NonNull Boolean> getReadOnlyHint() {
		return Optional.ofNullable(this.readOnlyHint);
	}

	/** @return destructive hint, preserving omission */
	@NonNull
	public Optional<@NonNull Boolean> getDestructiveHint() {
		return Optional.ofNullable(this.destructiveHint);
	}

	/** @return idempotent hint, preserving omission */
	@NonNull
	public Optional<@NonNull Boolean> getIdempotentHint() {
		return Optional.ofNullable(this.idempotentHint);
	}

	/** @return open-world interaction hint, preserving omission */
	@NonNull
	public Optional<@NonNull Boolean> getOpenWorldHint() {
		return Optional.ofNullable(this.openWorldHint);
	}

	/**
	 * Mutable builder for immutable {@link McpToolAnnotations}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@Nullable
		private String title;
		@Nullable
		private Boolean readOnlyHint;
		@Nullable
		private Boolean destructiveHint;
		@Nullable
		private Boolean idempotentHint;
		@Nullable
		private Boolean openWorldHint;

		private Builder() {
		}

		/**
		 * Sets the human-readable title hint.
		 *
		 * @param title title hint
		 * @return this builder
		 */
		@NonNull
		public Builder title(@NonNull String title) {
			this.title = requireNonNull(title);
			return this;
		}

		/** @param readOnly whether the tool is read-only
		 * @return this builder */
		@NonNull
		public Builder readOnlyHint(boolean readOnly) {
			this.readOnlyHint = readOnly;
			return this;
		}

		/** @param destructive whether the tool may perform destructive updates
		 * @return this builder */
		@NonNull
		public Builder destructiveHint(boolean destructive) {
			this.destructiveHint = destructive;
			return this;
		}

		/** @param idempotent whether repeated calls have no additional effect
		 * @return this builder */
		@NonNull
		public Builder idempotentHint(boolean idempotent) {
			this.idempotentHint = idempotent;
			return this;
		}

		/** @param openWorld whether the tool may interact with external entities
		 * @return this builder */
		@NonNull
		public Builder openWorldHint(boolean openWorld) {
			this.openWorldHint = openWorld;
			return this;
		}

		/** @return immutable tool annotations */
		@NonNull
		public McpToolAnnotations build() {
			return new McpToolAnnotations(this);
		}
	}
}
