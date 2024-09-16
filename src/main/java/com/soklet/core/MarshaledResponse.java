/*
 * Copyright 2022-2024 Revetware LLC.
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

import com.soklet.internal.spring.LinkedCaseInsensitiveMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class MarshaledResponse {
	@Nonnull
	private final Integer statusCode;
	@Nonnull
	private final Map<String, Set<String>> headers;
	@Nonnull
	private final Set<ResponseCookie> cookies;
	@Nullable
	private final byte[] body;

	@Nonnull
	public static Builder withStatusCode(@Nonnull Integer statusCode) {
		requireNonNull(statusCode);
		return new Builder(statusCode);
	}

	protected MarshaledResponse(@Nonnull Builder builder) {
		requireNonNull(builder);

		this.statusCode = builder.statusCode;
		this.headers = builder.headers == null ? Map.of() : new LinkedCaseInsensitiveMap<>(builder.headers);
		this.cookies = builder.cookies == null ? Set.of() : new LinkedHashSet<>(builder.cookies);
		this.body = builder.body;
	}

	@Override
	public String toString() {
		return format("%s{statusCode=%s, headers=%s, cookies=%s, body=%s}", getClass().getSimpleName(),
				getStatusCode(), getHeaders(), getCookies(),
				format("%d bytes", getBody().isPresent() ? getBody().get().length : 0));
	}

	@Nonnull
	public Copier copy() {
		return new Copier(this);
	}

	@Nonnull
	public Integer getStatusCode() {
		return this.statusCode;
	}

	@Nonnull
	public Map<String, Set<String>> getHeaders() {
		return this.headers;
	}

	@Nonnull
	public Set<ResponseCookie> getCookies() {
		return this.cookies;
	}

	@Nonnull
	public Optional<byte[]> getBody() {
		return Optional.ofNullable(this.body);
	}

	/**
	 * Builder used to construct instances of {@link MarshaledResponse} via {@link MarshaledResponse#withStatusCode(Integer)}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Builder {
		@Nonnull
		private Integer statusCode;
		@Nullable
		private Set<ResponseCookie> cookies;
		@Nullable
		private Map<String, Set<String>> headers;
		@Nullable
		private byte[] body;

		protected Builder(@Nonnull Integer statusCode) {
			requireNonNull(statusCode);
			this.statusCode = statusCode;
		}

		@Nonnull
		public Builder statusCode(@Nonnull Integer statusCode) {
			requireNonNull(statusCode);
			this.statusCode = statusCode;
			return this;
		}

		@Nonnull
		public Builder cookies(@Nullable Set<ResponseCookie> cookies) {
			this.cookies = cookies;
			return this;
		}

		@Nonnull
		public Builder headers(@Nullable Map<String, Set<String>> headers) {
			this.headers = headers;
			return this;
		}

		@Nonnull
		public Builder body(@Nullable byte[] body) {
			this.body = body;
			return this;
		}

		@Nonnull
		public MarshaledResponse build() {
			return new MarshaledResponse(this);
		}
	}

	/**
	 * Builder used to copy instances of {@link MarshaledResponse} via {@link MarshaledResponse#copy()}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Copier {
		@Nonnull
		private final Builder builder;

		Copier(@Nonnull MarshaledResponse marshaledResponse) {
			requireNonNull(marshaledResponse);

			this.builder = new Builder(marshaledResponse.getStatusCode())
					.headers(new LinkedCaseInsensitiveMap<>(marshaledResponse.getHeaders()))
					.cookies(new LinkedHashSet<>(marshaledResponse.getCookies()))
					.body(marshaledResponse.getBody().orElse(null));
		}

		@Nonnull
		public Copier statusCode(@Nonnull Integer statusCode) {
			requireNonNull(statusCode);
			this.builder.statusCode(statusCode);
			return this;
		}

		@Nonnull
		public Copier headers(@Nonnull Map<String, Set<String>> headers) {
			this.builder.headers(headers);
			return this;
		}

		// Convenience method for mutation
		@Nonnull
		public Copier headers(@Nonnull Consumer<Map<String, Set<String>>> headersConsumer) {
			requireNonNull(headersConsumer);
			headersConsumer.accept(this.builder.headers);
			return this;
		}

		@Nonnull
		public Copier cookies(@Nullable Set<ResponseCookie> cookies) {
			this.builder.cookies(cookies);
			return this;
		}

		// Convenience method for mutation
		@Nonnull
		public Copier cookies(@Nonnull Consumer<Set<ResponseCookie>> cookiesConsumer) {
			requireNonNull(cookiesConsumer);
			cookiesConsumer.accept(this.builder.cookies);
			return this;
		}

		@Nonnull
		public Copier body(@Nullable byte[] body) {
			this.builder.body(body);
			return this;
		}

		@Nonnull
		public MarshaledResponse finish() {
			return this.builder.build();
		}
	}
}
