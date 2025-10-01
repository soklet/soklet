/*
 * Copyright 2022-2025 Revetware LLC.
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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Response headers to send over the wire for <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS">non-preflight CORS</a> requests.
 * <p>
 * See <a href="https://www.soklet.com/docs/cors#writing-cors-responses">https://www.soklet.com/docs/cors#writing-cors-responses</a> for detailed documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class CorsResponse {
	@Nonnull
	private final String accessControlAllowOrigin;
	@Nullable
	private final Boolean accessControlAllowCredentials;
	@Nonnull
	private final Set<String> accessControlExposeHeaders;

	/**
	 * Acquires a builder for {@link CorsResponse} instances.
	 *
	 * @param accessControlAllowOrigin the required <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Allow-Origin">{@code Access-Control-Allow-Origin}</a> response header value
	 * @return the builder
	 */
	@Nonnull
	public static Builder withAccessControlAllowOrigin(@Nonnull String accessControlAllowOrigin) {
		requireNonNull(accessControlAllowOrigin);
		return new Builder(accessControlAllowOrigin);
	}

	/**
	 * Vends a mutable copier seeded with this instance's data, suitable for building new instances.
	 *
	 * @return a copier for this instance
	 */
	@Nonnull
	public Copier copy() {
		return new Copier(this);
	}

	protected CorsResponse(@Nonnull Builder builder) {
		requireNonNull(builder);

		this.accessControlAllowOrigin = builder.accessControlAllowOrigin;
		this.accessControlAllowCredentials = builder.accessControlAllowCredentials;
		this.accessControlExposeHeaders = builder.accessControlExposeHeaders == null ?
				Set.of() : Set.copyOf(builder.accessControlExposeHeaders);
	}

	@Override
	public String toString() {
		return format("%s{accessControlAllowOrigin=%s, accessControlAllowCredentials=%s, accessControlExposeHeaders=%s}",
				getClass().getSimpleName(), getAccessControlAllowOrigin(), getAccessControlAllowCredentials(),
				getAccessControlExposeHeaders());
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof CorsResponse corsResponse))
			return false;

		return Objects.equals(getAccessControlAllowOrigin(), corsResponse.getAccessControlAllowOrigin())
				&& Objects.equals(getAccessControlAllowCredentials(), corsResponse.getAccessControlAllowCredentials())
				&& Objects.equals(getAccessControlExposeHeaders(), corsResponse.getAccessControlExposeHeaders());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getAccessControlAllowOrigin(), getAccessControlAllowCredentials(),
				getAccessControlExposeHeaders());
	}

	/**
	 * Value for the <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Allow-Origin">{@code Access-Control-Allow-Origin}</a> response header.
	 *
	 * @return the header value
	 */
	@Nonnull
	public String getAccessControlAllowOrigin() {
		return this.accessControlAllowOrigin;
	}

	/**
	 * Value for the <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Allow-Credentials">{@code Access-Control-Allow-Credentials}</a> response header.
	 *
	 * @return the header value
	 */
	@Nonnull
	public Optional<Boolean> getAccessControlAllowCredentials() {
		return Optional.ofNullable(this.accessControlAllowCredentials);
	}

	/**
	 * Value for the <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Expose-Headers">{@code Access-Control-Expose-Headers}</a> response header.
	 *
	 * @return the header value
	 */
	@Nonnull
	public Set<String> getAccessControlExposeHeaders() {
		return this.accessControlExposeHeaders;
	}

	/**
	 * Builder used to construct instances of {@link CorsResponse} via {@link CorsResponse#withAccessControlAllowOrigin(String)}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Builder {
		@Nonnull
		private String accessControlAllowOrigin;
		@Nullable
		private Boolean accessControlAllowCredentials;
		@Nullable
		private Set<String> accessControlExposeHeaders;

		protected Builder(@Nonnull String accessControlAllowOrigin) {
			requireNonNull(accessControlAllowOrigin);
			this.accessControlAllowOrigin = accessControlAllowOrigin;
		}

		@Nonnull
		public Builder accessControlAllowOrigin(@Nonnull String accessControlAllowOrigin) {
			requireNonNull(accessControlAllowOrigin);
			this.accessControlAllowOrigin = accessControlAllowOrigin;
			return this;
		}

		@Nonnull
		public Builder accessControlAllowCredentials(@Nullable Boolean accessControlAllowCredentials) {
			this.accessControlAllowCredentials = accessControlAllowCredentials;
			return this;
		}

		@Nonnull
		public Builder accessControlExposeHeaders(@Nullable Set<String> accessControlExposeHeaders) {
			this.accessControlExposeHeaders = accessControlExposeHeaders;
			return this;
		}

		@Nonnull
		public CorsResponse build() {
			return new CorsResponse(this);
		}
	}

	/**
	 * Builder used to copy instances of {@link CorsResponse} via {@link CorsResponse#copy()}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Copier {
		@Nonnull
		private final Builder builder;

		Copier(@Nonnull CorsResponse corsResponse) {
			requireNonNull(corsResponse);

			this.builder = new Builder(corsResponse.getAccessControlAllowOrigin())
					.accessControlAllowCredentials(corsResponse.getAccessControlAllowCredentials().orElse(null))
					.accessControlExposeHeaders(new LinkedHashSet<>(corsResponse.getAccessControlExposeHeaders()));
		}

		@Nonnull
		public Copier accessControlAllowOrigin(@Nonnull String accessControlAllowOrigin) {
			requireNonNull(accessControlAllowOrigin);
			this.builder.accessControlAllowOrigin(accessControlAllowOrigin);
			return this;
		}

		@Nonnull
		public Copier accessControlAllowCredentials(@Nullable Boolean accessControlAllowCredentials) {
			this.builder.accessControlAllowCredentials(accessControlAllowCredentials);
			return this;
		}

		@Nonnull
		public Copier accessControlExposeHeaders(@Nullable Set<String> accessControlExposeHeaders) {
			this.builder.accessControlExposeHeaders(accessControlExposeHeaders);
			return this;
		}

		// Convenience method for mutation
		@Nonnull
		public Copier accessControlExposeHeaders(@Nonnull Consumer<Set<String>> accessControlExposeHeadersConsumer) {
			requireNonNull(accessControlExposeHeadersConsumer);

			if (this.builder.accessControlExposeHeaders == null)
				this.builder.accessControlExposeHeaders(new LinkedHashSet<>());

			accessControlExposeHeadersConsumer.accept(this.builder.accessControlExposeHeaders);
			return this;
		}

		@Nonnull
		public CorsResponse finish() {
			return this.builder.build();
		}
	}
}
