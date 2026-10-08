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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable icon descriptor for an MCP tool, prompt, or resource.
 *
 * <p>The source must be absolute and contain well-formed UTF-16. Its spelling
 * is retained without URI normalization or a scheme allowlist. MIME types use
 * the same ASCII media-type syntax parser as MCP content. Size hints must use
 * ASCII decimal {@code WxH} dimensions with a lowercase {@code x}, or exactly
 * {@code any}; order, duplicates, and decimal spelling are retained.</p>
 *
 * <p>Soklet does not fetch icons, verify dimensions or media bytes, establish
 * trusted source domains, or sanitize SVG. Applications and consuming clients
 * own source and rendering policy; prefer trusted HTTPS sources or image data
 * URIs. Construction does not prevalidate the eventual JSON response size.</p>
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
	 * @param source absolute icon source URI
	 * @return icon builder
	 * @throws IllegalArgumentException if the source is relative or contains an unpaired surrogate
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

	@NonNull
	private static URI requireSource(@NonNull URI source) {
		requireNonNull(source);
		if (!source.isAbsolute())
			throw new IllegalArgumentException("MCP icon sources must be absolute URIs.");
		McpContentValueSupport.requireWellFormedString(source.toString());
		return source;
	}

	private static void requireSize(@NonNull String size) {
		requireNonNull(size);
		if (size.equals("any"))
			return;
		int separator = size.indexOf('x');
		if (separator <= 0 || separator == size.length() - 1)
			throw invalidSize();
		for (int index = 0; index < size.length(); ++index) {
			if (index == separator)
				continue;
			char character = size.charAt(index);
			if (character < '0' || character > '9')
				throw invalidSize();
		}
	}

	@NonNull
	private static IllegalArgumentException invalidSize() {
		return new IllegalArgumentException(
				"MCP icon sizes must use ASCII WxH dimensions or 'any'.");
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

	/** @return whether every icon property is structurally equal */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpIcon icon))
			return false;
		return this.source.equals(icon.source)
				&& Objects.equals(this.mimeType, icon.mimeType)
				&& this.sizes.equals(icon.sizes)
				&& Objects.equals(this.theme, icon.theme);
	}

	/** @return structural icon hash code */
	@Override
	public int hashCode() {
		return Objects.hash(this.source, this.mimeType, this.sizes, this.theme);
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
		private List<@NonNull String> sizes = List.of();
		@Nullable
		private McpIconTheme theme;

		private Builder(@NonNull URI source) {
			this.source = requireSource(source);
		}

		/**
		 * Sets the icon MIME type.
		 *
		 * @param mimeType MIME type
		 * @return this builder
		 * @throws IllegalArgumentException if the MIME type has malformed syntax
		 */
		@NonNull
		public Builder mimeType(@NonNull String mimeType) {
			this.mimeType = McpContentValueSupport.requireMimeType(mimeType);
			return this;
		}

		/**
		 * Replaces advertised icon sizes in supplied order.
		 * Null or empty clears the property. The complete list is validated and
		 * snapshotted before replacing the prior value.
		 *
		 * @param sizes ASCII decimal WxH dimensions or {@code any}, or null to clear
		 * @return this builder
		 * @throws IllegalArgumentException if a size has malformed syntax
		 * @throws NullPointerException if a list element is null
		 */
		@NonNull
		public Builder sizes(
				@Nullable List<@NonNull String> sizes) {
			List<String> replacement = sizes == null ? List.of() : List.copyOf(sizes);
			replacement.forEach(McpIcon::requireSize);
			this.sizes = replacement;
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
