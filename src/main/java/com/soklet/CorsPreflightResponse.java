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
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Response headers to send over the wire for <a href="https://developer.mozilla.org/en-US/docs/Glossary/Preflight_request">CORS preflight</a> requests.
 * <p>
 * Instances can be acquired via the {@link #withAccessControlAllowOrigin(String)} builder factory method.
 * <p>
 * See <a href="https://www.soklet.com/docs/cors#writing-cors-responses">https://www.soklet.com/docs/cors#writing-cors-responses</a> for detailed documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class CorsPreflightResponse {
	@NonNull
	private final String accessControlAllowOrigin;
	@Nullable
	private final Boolean accessControlAllowCredentials;
	@Nullable
	private final Duration accessControlMaxAge;
	@NonNull
	private final Set<HttpMethod> accessControlAllowMethods;
	@NonNull
	private final Set<String> accessControlAllowHeaders;

	/**
	 * Acquires a builder for {@link CorsPreflightResponse} instances.
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

	protected CorsPreflightResponse(@NonNull Builder builder) {
		requireNonNull(builder);

		this.accessControlAllowOrigin = builder.accessControlAllowOrigin;
		this.accessControlAllowCredentials = builder.accessControlAllowCredentials;
		this.accessControlMaxAge = builder.accessControlMaxAge;
		this.accessControlAllowMethods = builder.accessControlAllowMethods == null ?
				Set.of() : Set.copyOf(builder.accessControlAllowMethods);
		this.accessControlAllowHeaders = builder.accessControlAllowHeaders == null ?
				Set.of() : Set.copyOf(builder.accessControlAllowHeaders);
	}

	@Override
	public String toString() {
		return format("%s{accessControlAllowOrigin=%s, accessControlAllowCredentials=%s, " +
						"accessControlMaxAge=%s, accessControlAllowMethods=%s, accessControlAllowHeaders=%s}",
				getClass().getSimpleName(), getAccessControlAllowOrigin(), getAccessControlAllowCredentials(),
				getAccessControlMaxAge(), getAccessControlAllowMethods(), getAccessControlAllowHeaders());
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof CorsPreflightResponse corsPreflightResponse))
			return false;

		return Objects.equals(getAccessControlAllowOrigin(), corsPreflightResponse.getAccessControlAllowOrigin())
				&& Objects.equals(getAccessControlAllowCredentials(), corsPreflightResponse.getAccessControlAllowCredentials())
				&& Objects.equals(getAccessControlMaxAge(), corsPreflightResponse.getAccessControlMaxAge())
				&& Objects.equals(getAccessControlAllowMethods(), corsPreflightResponse.getAccessControlAllowMethods())
				&& Objects.equals(getAccessControlAllowHeaders(), corsPreflightResponse.getAccessControlAllowHeaders());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getAccessControlAllowOrigin(), getAccessControlAllowCredentials(),
				getAccessControlMaxAge(), getAccessControlAllowMethods(), getAccessControlAllowHeaders());
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
	 * @return the header value, or {@link Optional#empty()} if not specified
	 */
	@NonNull
	public Optional<Boolean> getAccessControlAllowCredentials() {
		return Optional.ofNullable(this.accessControlAllowCredentials);
	}

	/**
	 * Value for the <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Max-Age">{@code Access-Control-Max-Age}</a> response header.
	 *
	 * @return the header value, or {@link Optional#empty()} if not specified
	 */
	@NonNull
	public Optional<Duration> getAccessControlMaxAge() {
		return Optional.ofNullable(this.accessControlMaxAge);
	}

	/**
	 * Set of values for the <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Allow-Methods">{@code Access-Control-Allow-Methods}</a> response header.
	 *
	 * @return the header values, or the empty set if not specified
	 */
	@NonNull
	public Set<HttpMethod> getAccessControlAllowMethods() {
		return this.accessControlAllowMethods;
	}

	/**
	 * Set of values for the <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Allow-Headers">{@code Access-Control-Allow-Headers}</a> response header.
	 *
	 * @return the header values, or the empty set if not specified
	 */
	@NonNull
	public Set<String> getAccessControlAllowHeaders() {
		return this.accessControlAllowHeaders;
	}

	/**
	 * Builder used to construct instances of {@link CorsPreflightResponse} via {@link CorsPreflightResponse#withAccessControlAllowOrigin(String)}.
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
		private Duration accessControlMaxAge;
		@Nullable
		private Set<HttpMethod> accessControlAllowMethods;
		@Nullable
		private Set<String> accessControlAllowHeaders;

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
		public Builder accessControlMaxAge(@Nullable Duration accessControlMaxAge) {
			this.accessControlMaxAge = accessControlMaxAge;
			return this;
		}

		@NonNull
		public Builder accessControlAllowMethods(@Nullable Set<HttpMethod> accessControlAllowMethods) {
			this.accessControlAllowMethods = accessControlAllowMethods;
			return this;
		}

		@NonNull
		public Builder accessControlAllowHeaders(@Nullable Set<String> accessControlAllowHeaders) {
			this.accessControlAllowHeaders = accessControlAllowHeaders;
			return this;
		}

		@NonNull
		public CorsPreflightResponse build() {
			return new CorsPreflightResponse(this);
		}
	}

	/**
	 * Builder used to copy instances of {@link CorsPreflightResponse} via {@link CorsPreflightResponse#copy()}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Copier {
		@NonNull
		private final Builder builder;

		Copier(@NonNull CorsPreflightResponse corsPreflightResponse) {
			requireNonNull(corsPreflightResponse);

			this.builder = new Builder(corsPreflightResponse.getAccessControlAllowOrigin())
					.accessControlAllowCredentials(corsPreflightResponse.getAccessControlAllowCredentials().orElse(null))
					.accessControlMaxAge(corsPreflightResponse.getAccessControlMaxAge().orElse(null))
					.accessControlAllowMethods(new LinkedHashSet<>(corsPreflightResponse.getAccessControlAllowMethods()))
					.accessControlAllowHeaders(new LinkedHashSet<>(corsPreflightResponse.getAccessControlAllowHeaders()));
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
		public Copier accessControlMaxAge(@Nullable Duration accessControlMaxAge) {
			this.builder.accessControlMaxAge(accessControlMaxAge);
			return this;
		}

		@NonNull
		public Copier accessControlAllowMethods(@Nullable Set<HttpMethod> accessControlAllowMethods) {
			this.builder.accessControlAllowMethods(accessControlAllowMethods);
			return this;
		}

		// Convenience method for mutation
		@NonNull
		public Copier accessControlAllowMethods(@NonNull Consumer<Set<HttpMethod>> accessControlAllowMethodsConsumer) {
			requireNonNull(accessControlAllowMethodsConsumer);

			if (this.builder.accessControlAllowMethods == null)
				this.builder.accessControlAllowMethods(new LinkedHashSet<>());

			accessControlAllowMethodsConsumer.accept(this.builder.accessControlAllowMethods);
			return this;
		}

		@NonNull
		public Copier accessControlAllowHeaders(@Nullable Set<String> accessControlAllowHeaders) {
			this.builder.accessControlAllowHeaders(accessControlAllowHeaders);
			return this;
		}

		// Convenience method for mutation
		@NonNull
		public Copier accessControlAllowHeaders(@NonNull Consumer<Set<String>> accessControlAllowHeadersConsumer) {
			requireNonNull(accessControlAllowHeadersConsumer);

			if (this.builder.accessControlAllowHeaders == null)
				this.builder.accessControlAllowHeaders(new LinkedHashSet<>());

			accessControlAllowHeadersConsumer.accept(this.builder.accessControlAllowHeaders);
			return this;
		}

		@NonNull
		public CorsPreflightResponse finish() {
			return this.builder.build();
		}
	}
}
