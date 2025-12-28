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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
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
 * Alternatively, your <em>Resource Method</em> might want to directly serve bytes to clients (e.g. an image or PDF) and skip the {@link ResponseMarshaler} entirely.
 * To accomplish this, just have your <em>Resource Method</em> return a {@link MarshaledResponse} instance: this tells Soklet "I already know exactly what bytes I want to send; don't go through the normal marshaling process".
 * <p>
 * Instances can be acquired via the {@link #withResponse(Response)} or {@link #withStatusCode(Integer)} builder factory methods.
 * <p>
 * Full documentation is available at <a href="https://www.soklet.com/docs/response-writing">https://www.soklet.com/docs/response-writing</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class MarshaledResponse {
	@NonNull
	private final Integer statusCode;
	@NonNull
	private final Map<String, Set<String>> headers;
	@NonNull
	private final Set<ResponseCookie> cookies;
	@Nullable
	private final byte[] body;

	/**
	 * Acquires a builder for {@link MarshaledResponse} instances.
	 *
	 * @param response the logical response whose values are used to prime this builder
	 * @return the builder
	 */
	@NonNull
	public static Builder withResponse(@NonNull Response response) {
		requireNonNull(response);

		Object rawBody = response.getBody().orElse(null);
		byte[] body = null;

		if (rawBody != null && rawBody instanceof byte[] byteArrayBody)
			body = byteArrayBody;

		return new Builder(response.getStatusCode())
				.headers(response.getHeaders())
				.cookies(response.getCookies())
				.body(body);
	}

	/**
	 * Acquires a builder for {@link MarshaledResponse} instances.
	 *
	 * @param statusCode the HTTP status code for this response
	 * @return the builder
	 */
	@NonNull
	public static Builder withStatusCode(@NonNull Integer statusCode) {
		requireNonNull(statusCode);
		return new Builder(statusCode);
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

	protected MarshaledResponse(@NonNull Builder builder) {
		requireNonNull(builder);

		this.statusCode = builder.statusCode;
		this.headers = builder.headers == null ? Map.of() : new LinkedCaseInsensitiveMap<>(builder.headers);
		this.cookies = builder.cookies == null ? Set.of() : new LinkedHashSet<>(builder.cookies);
		this.body = builder.body;

		// Verify headers are legal
		for (Entry<String, Set<String>> entry : this.headers.entrySet()) {
			String headerName = entry.getKey();
			Set<String> headerValues = entry.getValue();

			for (String headerValue : headerValues)
				Utilities.validateHeaderNameAndValue(headerName, headerValue);
		}
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
	@NonNull
	public Integer getStatusCode() {
		return this.statusCode;
	}

	/**
	 * The HTTP headers to write for this response.
	 *
	 * @return the headers to write
	 */
	@NonNull
	public Map<String, Set<String>> getHeaders() {
		return this.headers;
	}

	/**
	 * The HTTP cookies to write for this response.
	 *
	 * @return the cookies to write
	 */
	@NonNull
	public Set<ResponseCookie> getCookies() {
		return this.cookies;
	}

	/**
	 * The HTTP response body to write, if available.
	 *
	 * @return the response body to write, or {@link Optional#empty()}) if no body should be written
	 */
	@NonNull
	public Optional<byte[]> getBody() {
		return Optional.ofNullable(this.body);
	}

	/**
	 * Builder used to construct instances of {@link MarshaledResponse} via {@link MarshaledResponse#withResponse(Response)} or {@link MarshaledResponse#withStatusCode(Integer)}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private Integer statusCode;
		@Nullable
		private Set<ResponseCookie> cookies;
		@Nullable
		private Map<String, Set<String>> headers;
		@Nullable
		private byte[] body;

		protected Builder(@NonNull Integer statusCode) {
			requireNonNull(statusCode);
			this.statusCode = statusCode;
		}

		@NonNull
		public Builder statusCode(@NonNull Integer statusCode) {
			requireNonNull(statusCode);
			this.statusCode = statusCode;
			return this;
		}

		@NonNull
		public Builder cookies(@Nullable Set<ResponseCookie> cookies) {
			this.cookies = cookies;
			return this;
		}

		@NonNull
		public Builder headers(@Nullable Map<String, Set<String>> headers) {
			this.headers = headers;
			return this;
		}

		@NonNull
		public Builder body(@Nullable byte[] body) {
			this.body = body;
			return this;
		}

		@NonNull
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
	public static final class Copier {
		@NonNull
		private final Builder builder;

		Copier(@NonNull MarshaledResponse marshaledResponse) {
			requireNonNull(marshaledResponse);

			this.builder = new Builder(marshaledResponse.getStatusCode())
					.headers(new LinkedCaseInsensitiveMap<>(marshaledResponse.getHeaders()))
					.cookies(new LinkedHashSet<>(marshaledResponse.getCookies()))
					.body(marshaledResponse.getBody().orElse(null));
		}

		@NonNull
		public Copier statusCode(@NonNull Integer statusCode) {
			requireNonNull(statusCode);
			this.builder.statusCode(statusCode);
			return this;
		}

		@NonNull
		public Copier headers(@NonNull Map<String, Set<String>> headers) {
			this.builder.headers(headers);
			return this;
		}

		// Convenience method for mutation
		@NonNull
		public Copier headers(@NonNull Consumer<Map<String, Set<String>>> headersConsumer) {
			requireNonNull(headersConsumer);

			if (this.builder.headers == null)
				this.builder.headers(new LinkedCaseInsensitiveMap<>());

			headersConsumer.accept(this.builder.headers);
			return this;
		}

		@NonNull
		public Copier cookies(@Nullable Set<ResponseCookie> cookies) {
			this.builder.cookies(cookies);
			return this;
		}

		// Convenience method for mutation
		@NonNull
		public Copier cookies(@NonNull Consumer<Set<ResponseCookie>> cookiesConsumer) {
			requireNonNull(cookiesConsumer);

			if (this.builder.cookies == null)
				this.builder.cookies(new LinkedHashSet<>());

			cookiesConsumer.accept(this.builder.cookies);
			return this;
		}

		@NonNull
		public Copier body(@Nullable byte[] body) {
			this.builder.body(body);
			return this;
		}

		@NonNull
		public MarshaledResponse finish() {
			return this.builder.build();
		}
	}
}
