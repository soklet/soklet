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

import com.soklet.internal.spring.LinkedCaseInsensitiveMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Represents a logical HTTP response returned by a <em>Resource Method</em>.
 * <p>
 * Your application's {@link ResponseMarshaler} is responsible for taking the {@link Response} returned by a <em>Resource Method</em> as input
 * and creating a finalized binary representation ({@link MarshaledResponse}), suitable for sending to clients over the wire.
 * <p>
 * Instances can be acquired via these builder factory methods:
 * <ul>
 *   <li>{@link #withStatusCode(Integer)} (builder primed with status code)</li>
 *   <li>{@link #withRedirect(RedirectType, String)} (builder primed with redirect info)</li>
 * </ul>
 * <p>
 * Full documentation is available at <a href="https://www.soklet.com/docs/response-writing">https://www.soklet.com/docs/response-writing</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class Response {
	@Nonnull
	private final Integer statusCode;
	@Nonnull
	private final Set<ResponseCookie> cookies;
	@Nonnull
	private final Map<String, Set<String>> headers;
	@Nullable
	private final Object body;

	/**
	 * Acquires a builder for {@link Response} instances.
	 *
	 * @param statusCode the HTTP status code for this request ({@code 200, 201, etc.})
	 * @return the builder
	 */
	@Nonnull
	public static Builder withStatusCode(@Nonnull Integer statusCode) {
		requireNonNull(statusCode);
		return new Builder(statusCode);
	}

	/**
	 * Acquires a builder for {@link Response} instances that are intended to redirect the client.
	 *
	 * @param redirectType the kind of redirect to perform, for example {@link RedirectType#HTTP_307_TEMPORARY_REDIRECT}
	 * @param location     the URL to redirect to
	 * @return the builder
	 */
	@Nonnull
	public static Builder withRedirect(@Nonnull RedirectType redirectType,
																		 @Nonnull String location) {
		requireNonNull(redirectType);
		requireNonNull(location);
		return new Builder(redirectType, location);
	}

	private Response(@Nonnull Builder builder) {
		requireNonNull(builder);

		Map<String, Set<String>> headers = builder.headers == null
				? new LinkedCaseInsensitiveMap<>()
				: new LinkedCaseInsensitiveMap<>(builder.headers);

		if (builder.location != null && !headers.containsKey("Location"))
			headers.put("Location", Set.of(builder.location));

		// Verify headers are legal
		for (Entry<String, Set<String>> entry : headers.entrySet()) {
			String headerName = entry.getKey();
			Set<String> headerValues = entry.getValue();

			for (String headerValue : headerValues)
				Utilities.validateHeaderNameAndValue(headerName, headerValue);
		}

		Set<ResponseCookie> cookies = builder.cookies == null
				? Collections.emptySet()
				: new LinkedHashSet<>(builder.cookies);

		this.statusCode = builder.statusCode;
		this.cookies = Collections.unmodifiableSet(cookies);
		this.headers = Collections.unmodifiableMap(headers);
		this.body = builder.body;
	}

	@Override
	public String toString() {
		return format("%s{statusCode=%s, cookies=%s, headers=%s, body=%s}",
				getClass().getSimpleName(), getStatusCode(), getCookies(), getHeaders(), getBody());
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof Response response))
			return false;

		return Objects.equals(getStatusCode(), response.getStatusCode())
				&& Objects.equals(getCookies(), response.getCookies())
				&& Objects.equals(getHeaders(), response.getHeaders())
				&& Objects.equals(getBody(), response.getBody());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getStatusCode(), getCookies(), getHeaders(), getBody());
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

	/**
	 * The HTTP status code to be written to the client for this response.
	 * <p>
	 * See {@link StatusCode} for an enumeration of all HTTP status codes.
	 *
	 * @return the HTTP status code to write to the response
	 */
	@Nonnull
	public Integer getStatusCode() {
		return this.statusCode;
	}

	/**
	 * The cookies to be written to the client for this response.
	 * <p>
	 * It is possible to send multiple {@code ResponseCookie} values with the same name to the client.
	 * <p>
	 * <em>Note that {@code ResponseCookie} values, like all response headers, have case-insensitive names per the HTTP spec.</em>
	 *
	 * @return the cookies to write to the response
	 */
	@Nonnull
	public Set<ResponseCookie> getCookies() {
		return this.cookies;
	}

	/**
	 * The headers to be written to the client for this response.
	 * <p>
	 * The keys are the header names and the values are header values
	 * (it is possible to send the client multiple headers with the same name).
	 * <p>
	 * <em>Note that response headers have case-insensitive names per the HTTP spec.</em>
	 *
	 * @return the headers to write to the response
	 */
	@Nonnull
	public Map<String, Set<String>> getHeaders() {
		return this.headers;
	}

	/**
	 * The "logical" body content to be written to the response, if present.
	 * <p>
	 * It is the responsibility of the {@link ResponseMarshaler} to take this object and convert it into bytes to send over the wire.
	 *
	 * @return the object representing the response body, or {@link Optional#empty()} if no response body should be written
	 */
	@Nonnull
	public Optional<Object> getBody() {
		return Optional.ofNullable(this.body);
	}

	/**
	 * Builder used to construct instances of {@link Response} via {@link Response#withStatusCode(Integer)}
	 * or {@link Response#withRedirect(RedirectType, String)}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@Nonnull
		private Integer statusCode;
		@Nullable
		private String location;
		@Nullable
		private Set<ResponseCookie> cookies;
		@Nullable
		private Map<String, Set<String>> headers;
		@Nullable
		private Object body;

		protected Builder(@Nonnull Integer statusCode) {
			requireNonNull(statusCode);

			this.statusCode = statusCode;
			this.location = null;
		}

		protected Builder(@Nonnull RedirectType redirectType,
											@Nonnull String location) {
			requireNonNull(redirectType);
			requireNonNull(location);

			this.statusCode = redirectType.getStatusCode().getStatusCode();
			this.location = location;
		}

		@Nonnull
		public Builder statusCode(@Nonnull Integer statusCode) {
			requireNonNull(statusCode);

			this.statusCode = statusCode;
			this.location = null;
			return this;
		}

		@Nonnull
		public Builder redirect(@Nonnull RedirectType redirectType,
														@Nonnull String location) {
			requireNonNull(redirectType);
			requireNonNull(location);

			this.statusCode = redirectType.getStatusCode().getStatusCode();
			this.location = location;
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
		public Builder body(@Nullable Object body) {
			this.body = body;
			return this;
		}

		@Nonnull
		public Response build() {
			return new Response(this);
		}
	}

	/**
	 * Builder used to copy instances of {@link Response} via {@link Response#copy()}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Copier {
		@Nonnull
		private final Builder builder;

		Copier(@Nonnull Response response) {
			requireNonNull(response);

			this.builder = new Builder(response.getStatusCode())
					.headers(new LinkedCaseInsensitiveMap<>(response.getHeaders()))
					.cookies(new LinkedHashSet<>(response.getCookies()))
					.body(response.getBody().orElse(null));
		}

		@Nonnull
		public Copier statusCode(@Nonnull Integer statusCode) {
			requireNonNull(statusCode);
			this.builder.statusCode(statusCode);
			return this;
		}

		@Nonnull
		public Copier headers(@Nullable Map<String, Set<String>> headers) {
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
		public Copier body(@Nullable Object body) {
			this.builder.body(body);
			return this;
		}

		@Nonnull
		public Response finish() {
			return this.builder.build();
		}
	}
}
