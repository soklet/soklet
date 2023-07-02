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
import java.net.HttpCookie;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

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
	private final String reasonPhrase;
	@Nonnull
	private final Map<String, Set<String>> headers;
	@Nonnull
	private final Set<HttpCookie> cookies;
	@Nullable
	private final byte[] body;

	protected MarshaledResponse(@Nonnull Builder builder) {
		requireNonNull(builder);

		StatusCode typedStatusCode = StatusCode.fromStatusCode(builder.statusCode).orElse(null);

		this.statusCode = builder.statusCode;
		this.reasonPhrase = typedStatusCode == null ? "Unknown" : typedStatusCode.getReasonPhrase();
		this.headers = builder.headers == null ? Map.of() : Map.copyOf(builder.headers);
		this.cookies = builder.cookies == null ? Set.of() : Set.copyOf(builder.cookies);
		this.body = builder.body;
	}

	@Override
	public String toString() {
		return format("%s{statusCode=%s, reasonPhrase=%s, headers=%s, cookies=%s, body=%s}", getClass().getSimpleName(),
				getStatusCode(), getReasonPhrase(), getHeaders(), getCookies(),
				format("%d bytes", getBody().isPresent() ? getBody().get().length : 0));
	}

	@Nonnull
	public Copier copy() {
		return new Copier(this);
	}

	@Nonnull
	public Integer getStatusCode() {
		return statusCode;
	}

	@Nonnull
	public String getReasonPhrase() {
		return reasonPhrase;
	}

	@Nonnull
	public Map<String, Set<String>> getHeaders() {
		return headers;
	}

	@Nonnull
	public Set<HttpCookie> getCookies() {
		return cookies;
	}

	@Nonnull
	public Optional<byte[]> getBody() {
		return Optional.ofNullable(body);
	}

	/**
	 * Builder used to construct instances of {@link MarshaledResponse}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Builder {
		@Nonnull
		private final Integer statusCode;
		@Nullable
		private Set<HttpCookie> cookies;
		@Nullable
		private Map<String, Set<String>> headers;
		@Nullable
		private byte[] body;

		public Builder(@Nonnull Integer statusCode) {
			requireNonNull(statusCode);
			this.statusCode = statusCode;
		}

		@Nonnull
		public Builder cookies(@Nullable Set<HttpCookie> cookies) {
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
	 * Builder used to copy instances of {@link MarshaledResponse}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Copier {
		@Nonnull
		private Builder builder;

		Copier(@Nonnull MarshaledResponse marshaledResponse) {
			requireNonNull(marshaledResponse);

			this.builder = new MarshaledResponse.Builder(marshaledResponse.getStatusCode())
					.headers(new HashMap<>(marshaledResponse.getHeaders()))
					.cookies(new HashSet<>(marshaledResponse.getCookies()))
					.body(marshaledResponse.getBody().orElse(null));
		}

		@Nonnull
		public Copier statusCode(@Nonnull Function<Integer, Integer> statusCodeFunction) {
			requireNonNull(statusCodeFunction);

			this.builder = new MarshaledResponse.Builder(statusCodeFunction.apply(builder.statusCode))
					.headers(builder.headers == null ? null : new HashMap<>(builder.headers))
					.cookies(builder.cookies == null ? null : new HashSet<>(builder.cookies))
					.body(builder.body);

			return this;
		}

		@Nonnull
		public Copier headers(@Nonnull Consumer<Map<String, Set<String>>> headersConsumer) {
			requireNonNull(headersConsumer);

			headersConsumer.accept(builder.headers);
			return this;
		}

		@Nonnull
		public Copier cookies(@Nonnull Consumer<Set<HttpCookie>> cookiesConsumer) {
			requireNonNull(cookiesConsumer);

			cookiesConsumer.accept(builder.cookies);
			return this;
		}

		@Nonnull
		public Copier body(@Nonnull Function<byte[], byte[]> bodyFunction) {
			requireNonNull(bodyFunction);

			builder.body = bodyFunction.apply(builder.body);
			return this;
		}

		@Nonnull
		public MarshaledResponse finish() {
			return this.builder.build();
		}
	}
}
