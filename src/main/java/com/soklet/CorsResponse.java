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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
 * Instances can be acquired via the {@link #withAccessControlAllowOrigin(String)} builder factory method.
 * <p>
 * See <a href="https://www.soklet.com/docs/cors#writing-cors-responses">https://www.soklet.com/docs/cors#writing-cors-responses</a> for detailed documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class CorsResponse {
	@NonNull
	private final String accessControlAllowOrigin;
	@Nullable
	private final Boolean accessControlAllowCredentials;
	@NonNull
	private final Set<String> accessControlExposeHeaders;

	/**
	 * Acquires a builder for {@link CorsResponse} instances.
	 *
	 * @param accessControlAllowOrigin the required <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Allow-Origin">{@code Access-Control-Allow-Origin}</a> response header value
	 * @return the builder
	 */
	@NonNull
	public static Builder withAccessControlAllowOrigin(@NonNull String accessControlAllowOrigin) {
		requireNonNull(accessControlAllowOrigin);
		return new Builder(accessControlAllowOrigin);
	}

	/**
	 * Vends a mutable copier seeded with this instance's data, suitable for building new instances.
	 *
	 * @return a copier for this instance
	 */
	@NonNull
	public Copier copy() {
		return new Copier(this);
	}

	protected CorsResponse(@NonNull Builder builder) {
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
	@NonNull
	public String getAccessControlAllowOrigin() {
		return this.accessControlAllowOrigin;
	}

	/**
	 * Value for the <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Allow-Credentials">{@code Access-Control-Allow-Credentials}</a> response header.
	 *
	 * @return the header value
	 */
	@NonNull
	public Optional<Boolean> getAccessControlAllowCredentials() {
		return Optional.ofNullable(this.accessControlAllowCredentials);
	}

	/**
	 * Value for the <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Expose-Headers">{@code Access-Control-Expose-Headers}</a> response header.
	 *
	 * @return the header value
	 */
	@NonNull
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
	public static final class Builder {
		@NonNull
		private String accessControlAllowOrigin;
		@Nullable
		private Boolean accessControlAllowCredentials;
		@Nullable
		private Set<String> accessControlExposeHeaders;

		protected Builder(@NonNull String accessControlAllowOrigin) {
			requireNonNull(accessControlAllowOrigin);
			this.accessControlAllowOrigin = accessControlAllowOrigin;
		}

		@NonNull
		public Builder accessControlAllowOrigin(@NonNull String accessControlAllowOrigin) {
			requireNonNull(accessControlAllowOrigin);
			this.accessControlAllowOrigin = accessControlAllowOrigin;
			return this;
		}

		@NonNull
		public Builder accessControlAllowCredentials(@Nullable Boolean accessControlAllowCredentials) {
			this.accessControlAllowCredentials = accessControlAllowCredentials;
			return this;
		}

		@NonNull
		public Builder accessControlExposeHeaders(@Nullable Set<String> accessControlExposeHeaders) {
			this.accessControlExposeHeaders = accessControlExposeHeaders;
			return this;
		}

		@NonNull
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
	public static final class Copier {
		@NonNull
		private final Builder builder;

		Copier(@NonNull CorsResponse corsResponse) {
			requireNonNull(corsResponse);

			this.builder = new Builder(corsResponse.getAccessControlAllowOrigin())
					.accessControlAllowCredentials(corsResponse.getAccessControlAllowCredentials().orElse(null))
					.accessControlExposeHeaders(new LinkedHashSet<>(corsResponse.getAccessControlExposeHeaders()));
		}

		@NonNull
		public Copier accessControlAllowOrigin(@NonNull String accessControlAllowOrigin) {
			requireNonNull(accessControlAllowOrigin);
			this.builder.accessControlAllowOrigin(accessControlAllowOrigin);
			return this;
		}

		@NonNull
		public Copier accessControlAllowCredentials(@Nullable Boolean accessControlAllowCredentials) {
			this.builder.accessControlAllowCredentials(accessControlAllowCredentials);
			return this;
		}

		@NonNull
		public Copier accessControlExposeHeaders(@Nullable Set<String> accessControlExposeHeaders) {
			this.builder.accessControlExposeHeaders(accessControlExposeHeaders);
			return this;
		}

		// Convenience method for mutation
		@NonNull
		public Copier accessControlExposeHeaders(@NonNull Consumer<Set<String>> accessControlExposeHeadersConsumer) {
			requireNonNull(accessControlExposeHeadersConsumer);

			if (this.builder.accessControlExposeHeaders == null)
				this.builder.accessControlExposeHeaders(new LinkedHashSet<>());

			accessControlExposeHeadersConsumer.accept(this.builder.accessControlExposeHeaders);
			return this;
		}

		@NonNull
		public CorsResponse finish() {
			return this.builder.build();
		}
	}
}
