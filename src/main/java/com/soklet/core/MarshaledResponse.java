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
 * A finalized representation of a {@link Response}, suitable for sending to clients over the wire.
 * <p>
 * Your application's {@link ResponseMarshaler} is responsible for taking the {@link Response} returned by a <em>Resource Method</em> as input
 * and converting its {@link Response#getBody()} to a {@code byte[]}.
 * <p>
 * For example, if a {@link Response} were to specify a body of {@code List.of("one", "two")}, a {@link ResponseMarshaler} might
 * convert it to the JSON string {@code ["one", "two"]} and provide as output a corresponding {@link MarshaledResponse} with a body of UTF-8 bytes that represent {@code ["one", "two"]}.
 * <p>
 * Full documentation is available at <a href="https://www.soklet.com/docs/response-writing">https://www.soklet.com/docs/response-writing</a>.
 *
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

	/**
	 * Acquires a builder for {@link MarshaledResponse} instances.
	 *
	 * @param statusCode the HTTP status code for this response
	 * @return the builder
	 */
	@Nonnull
	public static Builder withStatusCode(@Nonnull Integer statusCode) {
		requireNonNull(statusCode);
		return new Builder(statusCode);
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

	/**
	 * The HTTP status code for this response.
	 *
	 * @return the status code
	 */
	@Nonnull
	public Integer getStatusCode() {
		return this.statusCode;
	}

	/**
	 * The HTTP headers to write for this response.
	 *
	 * @return the headers to write
	 */
	@Nonnull
	public Map<String, Set<String>> getHeaders() {
		return this.headers;
	}

	/**
	 * The HTTP cookies to write for this response.
	 *
	 * @return the cookies to write
	 */
	@Nonnull
	public Set<ResponseCookie> getCookies() {
		return this.cookies;
	}

	/**
	 * The HTTP response body to write, if available.
	 *
	 * @return the response body to write, or {@link Optional#empty()}) if no body should be written
	 */
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

			if (this.builder.headers == null)
				this.builder.headers(new LinkedCaseInsensitiveMap<>());

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

			if (this.builder.cookies == null)
				this.builder.cookies(new LinkedHashSet<>());

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
