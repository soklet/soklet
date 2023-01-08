/*
 * Copyright 2022 Revetware LLC.
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

package com.soklet.core;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
@ThreadSafe
public class CorsPreflightResponse {
	@Nonnull
	private final String accessControlAllowOrigin;
	@Nullable
	private final Boolean accessControlAllowCredentials;
	@Nullable
	private final Duration accessControlMaxAge;
	@Nonnull
	private final Set<HttpMethod> accessControlAllowMethods;
	@Nonnull
	private final Set<String> accessControlAllowHeaders;

	protected CorsPreflightResponse(@Nonnull Builder builder) {
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

	@Nonnull
	public String getAccessControlAllowOrigin() {
		return this.accessControlAllowOrigin;
	}

	@Nonnull
	public Optional<Boolean> getAccessControlAllowCredentials() {
		return Optional.ofNullable(this.accessControlAllowCredentials);
	}

	@Nonnull
	public Optional<Duration> getAccessControlMaxAge() {
		return Optional.ofNullable(this.accessControlMaxAge);
	}

	@Nonnull
	public Set<HttpMethod> getAccessControlAllowMethods() {
		return this.accessControlAllowMethods;
	}

	@Nonnull
	public Set<String> getAccessControlAllowHeaders() {
		return this.accessControlAllowHeaders;
	}

	/**
	 * Builder used to construct instances of {@link CorsPreflightResponse}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetware.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Builder {
		@Nonnull
		private final String accessControlAllowOrigin;
		@Nullable
		private Boolean accessControlAllowCredentials;
		@Nullable
		private Duration accessControlMaxAge;
		@Nullable
		private Set<HttpMethod> accessControlAllowMethods;
		@Nullable
		private Set<String> accessControlAllowHeaders;

		public Builder(@Nonnull String accessControlAllowOrigin) {
			requireNonNull(accessControlAllowOrigin);
			this.accessControlAllowOrigin = accessControlAllowOrigin;
		}

		@Nonnull
		public Builder accessControlAllowCredentials(@Nullable Boolean accessControlAllowCredentials) {
			this.accessControlAllowCredentials = accessControlAllowCredentials;
			return this;
		}

		@Nonnull
		public Builder accessControlMaxAge(@Nullable Duration accessControlMaxAge) {
			this.accessControlMaxAge = accessControlMaxAge;
			return this;
		}

		@Nonnull
		public Builder accessControlAllowMethods(@Nullable Set<HttpMethod> accessControlAllowMethods) {
			this.accessControlAllowMethods = accessControlAllowMethods;
			return this;
		}

		@Nonnull
		public Builder accessControlAllowHeaders(@Nullable Set<String> accessControlAllowHeaders) {
			this.accessControlAllowHeaders = accessControlAllowHeaders;
			return this;
		}

		@Nonnull
		public CorsPreflightResponse build() {
			return new CorsPreflightResponse(this);
		}
	}
}
