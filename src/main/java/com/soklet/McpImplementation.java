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
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable MCP implementation metadata.
 * <p>
 * Servers use this value to identify themselves to MCP clients. Client
 * implementation information received from the wire is informational and is
 * not an authenticated identity.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpImplementation {
	@NonNull
	private final String name;
	@NonNull
	private final String version;
	@Nullable
	private final String title;
	@Nullable
	private final String description;
	@Nullable
	private final URI websiteUrl;

	/**
	 * Vends a builder primed with the required implementation name and version.
	 *
	 * @param name    the nonblank implementation name
	 * @param version the nonblank implementation version
	 * @return a builder for implementation metadata
	 * @throws IllegalArgumentException if {@code name} or {@code version} is blank
	 */
	@NonNull
	public static Builder withNameAndVersion(@NonNull String name,
																	@NonNull String version) {
		return new Builder(requireNonBlank(name, "Implementation name"),
				requireNonBlank(version, "Implementation version"));
	}

	private McpImplementation(@NonNull Builder builder) {
		requireNonNull(builder);
		this.name = builder.name;
		this.version = builder.version;
		this.title = blankToNull(builder.title);
		this.description = blankToNull(builder.description);
		this.websiteUrl = builder.websiteUrl;
	}

	/**
	 * The implementation name.
	 *
	 * @return the nonblank implementation name
	 */
	@NonNull
	public String getName() {
		return this.name;
	}

	/**
	 * The implementation version.
	 *
	 * @return the nonblank implementation version
	 */
	@NonNull
	public String getVersion() {
		return this.version;
	}

	/**
	 * The optional human-readable implementation title.
	 *
	 * @return the title, or the empty optional if none was configured
	 */
	@NonNull
	public Optional<@NonNull String> getTitle() {
		return Optional.ofNullable(this.title);
	}

	/**
	 * The optional human-readable implementation description.
	 *
	 * @return the description, or the empty optional if none was configured
	 */
	@NonNull
	public Optional<@NonNull String> getDescription() {
		return Optional.ofNullable(this.description);
	}

	/**
	 * The optional absolute website URI for this implementation.
	 *
	 * @return the website URI, or the empty optional if none was configured
	 */
	@NonNull
	public Optional<@NonNull URI> getWebsiteUrl() {
		return Optional.ofNullable(this.websiteUrl);
	}

	@NonNull
	private static String requireNonBlank(@NonNull String value,
																					@NonNull String description) {
		requireNonNull(value);
		requireNonNull(description);

		if (value.isBlank())
			throw new IllegalArgumentException(description + " must not be blank.");

		return value;
	}

	@Nullable
	private static String blankToNull(@Nullable String value) {
		return value == null || value.isBlank() ? null : value;
	}

	/**
	 * Builder for immutable {@link McpImplementation} values.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final String name;
		@NonNull
		private final String version;
		@Nullable
		private String title;
		@Nullable
		private String description;
		@Nullable
		private URI websiteUrl;

		private Builder(@NonNull String name, @NonNull String version) {
			this.name = requireNonNull(name);
			this.version = requireNonNull(version);
		}

		/**
		 * Sets a human-readable title. A blank title is treated as absent.
		 *
		 * @param title the implementation title
		 * @return this builder
		 */
		@NonNull
		public Builder title(@NonNull String title) {
			this.title = requireNonNull(title);
			return this;
		}

		/**
		 * Sets a human-readable description. A blank description is treated as
		 * absent.
		 *
		 * @param description the implementation description
		 * @return this builder
		 */
		@NonNull
		public Builder description(@NonNull String description) {
			this.description = requireNonNull(description);
			return this;
		}

		/**
		 * Sets the implementation website URI.
		 *
		 * @param websiteUrl the absolute website URI
		 * @return this builder
		 * @throws IllegalArgumentException if the URI is relative
		 */
		@NonNull
		public Builder websiteUrl(@NonNull URI websiteUrl) {
			requireNonNull(websiteUrl);

			if (!websiteUrl.isAbsolute())
				throw new IllegalArgumentException(
						"Implementation website URL must be an absolute URI.");

			this.websiteUrl = websiteUrl;
			return this;
		}

		/**
		 * Builds immutable implementation metadata.
		 *
		 * @return the implementation metadata
		 */
		@NonNull
		public McpImplementation build() {
			return new McpImplementation(this);
		}
	}
}
