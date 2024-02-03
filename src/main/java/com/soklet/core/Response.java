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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
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
public class Response {
	@Nonnull
	private final Integer statusCode;
	@Nonnull
	private final Set<ResponseCookie> cookies;
	@Nonnull
	private final Map<String, Set<String>> headers;
	@Nullable
	private final Object body;

	protected Response(@Nonnull Builder builder) {
		requireNonNull(builder);

		Map<String, Set<String>> headers = builder.headers == null
				? new LinkedCaseInsensitiveMap<>()
				: new LinkedCaseInsensitiveMap<>(builder.headers);

		if (builder.location != null && !headers.containsKey("Location"))
			headers.put("Location", Set.of(builder.location));

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

	@Nonnull
	public Copier copy() {
		return new Copier(this);
	}

	@Nonnull
	public Integer getStatusCode() {
		return this.statusCode;
	}

	@Nonnull
	public Set<ResponseCookie> getCookies() {
		return this.cookies;
	}

	@Nonnull
	public Map<String, Set<String>> getHeaders() {
		return this.headers;
	}

	@Nonnull
	public Optional<Object> getBody() {
		return Optional.ofNullable(this.body);
	}

	/**
	 * Builder used to construct instances of {@link Response}.
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
		private final String location;
		@Nullable
		private Set<ResponseCookie> cookies;
		@Nullable
		private Map<String, Set<String>> headers;
		@Nullable
		private Object body;

		public Builder() {
			this(200);
		}

		public Builder(@Nonnull Integer statusCode) {
			requireNonNull(statusCode);

			this.statusCode = statusCode;
			this.location = null;
		}

		public Builder(@Nonnull RedirectType redirectType,
									 @Nonnull String location) {
			requireNonNull(redirectType);
			requireNonNull(location);

			this.statusCode = redirectType.getStatusCode().getStatusCode();
			this.location = location;
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
	 * Builder used to copy instances of {@link Response}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Copier {
		@Nonnull
		private Builder builder;

		Copier(@Nonnull Response response) {
			requireNonNull(response);

			this.builder = new Builder(response.getStatusCode())
					.headers(new LinkedCaseInsensitiveMap<>(response.getHeaders()))
					.cookies(new LinkedHashSet<>(response.getCookies()))
					.body(response.getBody().orElse(null));
		}

		@Nonnull
		public Copier statusCode(@Nonnull Function<Integer, Integer> statusCodeFunction) {
			requireNonNull(statusCodeFunction);

			this.builder = new Builder(statusCodeFunction.apply(builder.statusCode))
					.headers(builder.headers == null ? null : new LinkedCaseInsensitiveMap<>(builder.headers))
					.cookies(builder.cookies == null ? null : new LinkedHashSet<>(builder.cookies))
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
		public Copier cookies(@Nonnull Consumer<Set<ResponseCookie>> cookiesConsumer) {
			requireNonNull(cookiesConsumer);

			cookiesConsumer.accept(builder.cookies);
			return this;
		}

		@Nonnull
		public Copier body(@Nonnull Function<Object, Object> bodyFunction) {
			requireNonNull(bodyFunction);

			builder.body = bodyFunction.apply(builder.body);
			return this;
		}

		@Nonnull
		public Response finish() {
			return this.builder.build();
		}
	}
}
