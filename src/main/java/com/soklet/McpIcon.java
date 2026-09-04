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
 * Immutable icon descriptor for an MCP tool, prompt, or resource.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpIcon {
	@NonNull
	private final URI source;
	@Nullable
	private final String mimeType;
	@NonNull
	private final List<@NonNull String> sizes;
	@Nullable
	private final McpIconTheme theme;

	/**
	 * Vends a builder primed with the icon source URI.
	 *
	 * @param source icon source URI
	 * @return icon builder
	 */
	@NonNull
	public static Builder withSource(@NonNull URI source) {
		return new Builder(source);
	}

	private McpIcon(@NonNull Builder builder) {
		this.source = builder.source;
		this.mimeType = builder.mimeType;
		this.sizes = List.copyOf(builder.sizes);
		this.theme = builder.theme;
	}

	/** @return icon source URI */
	@NonNull
	public URI getSource() {
		return this.source;
	}

	/** @return MIME type, if supplied */
	@NonNull
	public Optional<@NonNull String> getMimeType() {
		return Optional.ofNullable(this.mimeType);
	}

	/** @return immutable advertised icon sizes */
	@NonNull
	public List<@NonNull String> getSizes() {
		return this.sizes;
	}

	/** @return preferred theme, if supplied */
	@NonNull
	public Optional<@NonNull McpIconTheme> getTheme() {
		return Optional.ofNullable(this.theme);
	}

	/**
	 * Mutable builder for an immutable {@link McpIcon}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final URI source;
		@Nullable
		private String mimeType;
		@NonNull
		private final List<@NonNull String> sizes = new ArrayList<>();
		@Nullable
		private McpIconTheme theme;

		private Builder(@NonNull URI source) {
			this.source = requireNonNull(source);
		}

		/**
		 * Sets the icon MIME type.
		 *
		 * @param mimeType MIME type
		 * @return this builder
		 */
		@NonNull
		public Builder mimeType(@NonNull String mimeType) {
			this.mimeType = requireNonNull(mimeType);
			return this;
		}

		/**
		 * Replaces the advertised icon sizes.
		 * Passing no sizes clears the advertised icon sizes.
		 *
		 * @param sizes size tokens such as {@code 48x48} or {@code any}
		 * @return this builder
		 */
		@NonNull
		public Builder sizes(@NonNull String... sizes) {
			requireNonNull(sizes);
			this.sizes.clear();
			for (String size : sizes)
				this.sizes.add(requireNonNull(size));
			return this;
		}

		/**
		 * Sets the preferred icon theme.
		 *
		 * @param theme icon theme
		 * @return this builder
		 */
		@NonNull
		public Builder theme(@NonNull McpIconTheme theme) {
			this.theme = requireNonNull(theme);
			return this;
		}

		/** @return immutable icon descriptor */
		@NonNull
		public McpIcon build() {
			return new McpIcon(this);
		}
	}
}
