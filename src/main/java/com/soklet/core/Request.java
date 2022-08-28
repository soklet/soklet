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

import com.soklet.core.impl.DefaultIdGenerator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.lang.String.format;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
@ThreadSafe
public class Request {
	@Nonnull
	private static final IdGenerator DEFAULT_ID_GENERATOR;

	@Nonnull
	private final Long id;
	@Nonnull
	private final HttpMethod httpMethod;
	@Nonnull
	private final String uri;
	@Nonnull
	private final String path;
	@Nonnull
	private final Set<HttpCookie> cookies;
	@Nonnull
	private final Map<String, Set<String>> queryParameters;
	@Nonnull
	private final Map<String, Set<String>> headers;
	@Nullable
	private final byte[] body;
	@Nullable
	private volatile String bodyAsString = null;
	@Nullable
	private volatile List<Locale> locales = null;
	@Nonnull
	private final ReentrantLock lock;

	static {
		DEFAULT_ID_GENERATOR = new DefaultIdGenerator();
	}

	protected Request(@Nonnull Builder builder) {
		requireNonNull(builder);

		IdGenerator idGenerator = builder.idGenerator == null ? getDefaultIdGenerator() : builder.idGenerator;

		this.lock = new ReentrantLock();
		this.id = builder.id == null ? idGenerator.generateId() : builder.id;
		this.httpMethod = builder.httpMethod;
		this.uri = builder.uri;
		this.path = Utilities.normalizedPathForUrl(builder.uri);
		this.cookies = builder.cookies == null ? Set.of() : Collections.unmodifiableSet(new HashSet<>(builder.cookies));
		this.queryParameters = Collections.unmodifiableMap(Utilities.extractQueryParametersFromUrl(builder.uri));

		// Header names are case-insensitive.  Enforce that here with a special map
		Map<String, Set<String>> caseInsensitiveHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

		if (builder.headers != null)
			caseInsensitiveHeaders.putAll(builder.headers);

		this.headers = Collections.unmodifiableMap(caseInsensitiveHeaders);
		this.body = builder.body;
	}

	@Override
	public String toString() {
		return format("%s{id=%s, httpMethod=%s, uri=%s, path=%s, cookies=%s, queryParameters=%s, headers=%s, body=%s}", getClass().getSimpleName(),
				getId(), getHttpMethod(), getUri(), getPath(), getCookies(), getQueryParameters(), getHeaders(),
				format("%d bytes", getBody().isPresent() ? getBody().get().length : 0));
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof Request))
			return false;

		Request request = (Request) object;

		return Objects.equals(getId(), request.getId())
				&& Objects.equals(getHttpMethod(), request.getHttpMethod())
				&& Objects.equals(getUri(), request.getUri())
				&& Objects.equals(getPath(), request.getPath())
				&& Objects.equals(getCookies(), request.getCookies())
				&& Objects.equals(getQueryParameters(), request.getQueryParameters())
				&& Objects.equals(getHeaders(), request.getHeaders())
				&& Objects.equals(getBody(), request.getBody());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getId(), getHttpMethod(), getUri(), getPath(), getCookies(), getQueryParameters(), getHeaders(), getBody());
	}

	@Nonnull
	public Copier copy() {
		return new Copier(this);
	}

	@Nonnull
	public Long getId() {
		return this.id;
	}

	@Nonnull
	public HttpMethod getHttpMethod() {
		return this.httpMethod;
	}

	@Nonnull
	public String getUri() {
		return this.uri;
	}

	@Nonnull
	public String getPath() {
		return this.path;
	}

	@Nonnull
	public Set<HttpCookie> getCookies() {
		return this.cookies;
	}

	@Nonnull
	public Map<String, Set<String>> getQueryParameters() {
		return this.queryParameters;
	}

	@Nonnull
	public Map<String, Set<String>> getHeaders() {
		return this.headers;
	}

	@Nonnull
	public Optional<byte[]> getBody() {
		return Optional.ofNullable(this.body);
	}

	@Nonnull
	public Optional<String> getBodyAsString() {
		// Lazily instantiate a string instance using double-checked locking
		if (this.body != null && this.bodyAsString == null) {
			getLock().lock();
			try {
				if (this.body != null && this.bodyAsString == null)
					this.bodyAsString = new String(this.body, StandardCharsets.UTF_8);
			} finally {
				getLock().unlock();
			}
		}

		return Optional.ofNullable(this.bodyAsString);
	}

	@Nonnull
	public List<Locale> getLocales() {
		// Lazily instantiate our parsed locales using double-checked locking
		if (this.locales == null) {
			getLock().lock();
			try {
				if (this.locales == null) {
					Set<String> acceptLanguageHeaderValue = getHeaders().get("Accept-Language");

					if (acceptLanguageHeaderValue != null && acceptLanguageHeaderValue.size() > 0)
						this.locales = unmodifiableList(Utilities.localesFromAcceptLanguageHeaderValue(acceptLanguageHeaderValue.stream().findFirst().get()));
					else
						this.locales = List.of();
				}
			} finally {
				getLock().unlock();
			}
		}

		return this.locales;
	}

	@Nonnull
	protected IdGenerator getDefaultIdGenerator() {
		return DEFAULT_ID_GENERATOR;
	}

	@Nonnull
	protected ReentrantLock getLock() {
		return this.lock;
	}

	/**
	 * Builder used to construct instances of {@link Request}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetware.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Builder {
		@Nonnull
		private final HttpMethod httpMethod;
		@Nonnull
		private final String uri;
		@Nullable
		private Long id;
		@Nullable
		private IdGenerator idGenerator;
		@Nullable
		private Set<HttpCookie> cookies;
		@Nullable
		private Map<String, Set<String>> headers;
		@Nullable
		private byte[] body;

		public Builder(@Nonnull HttpMethod httpMethod,
									 @Nonnull String uri) {
			requireNonNull(httpMethod);
			requireNonNull(uri);

			this.httpMethod = httpMethod;
			this.uri = uri;
		}

		@Nonnull
		public Builder id(@Nullable Long id) {
			this.id = id;
			return this;
		}

		@Nonnull
		public Builder idGenerator(@Nullable IdGenerator idGenerator) {
			this.idGenerator = idGenerator;
			return this;
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
		public Request build() {
			return new Request(this);
		}
	}

	/**
	 * Builder used to construct instances of {@link Request}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetware.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Copier {
		@Nonnull
		private Builder builder;

		Copier(@Nonnull Request request) {
			requireNonNull(request);

			this.builder = new Builder(request.getHttpMethod(), request.getUri())
					.id(request.getId())
					.headers(new HashMap<>(request.getHeaders()))
					.cookies(new HashSet<>(request.getCookies()))
					.body(request.getBody().orElse(null));
		}

		@Nonnull
		public Copier httpMethod(@Nonnull Function<HttpMethod, HttpMethod> httpMethodFunction) {
			requireNonNull(httpMethodFunction);

			this.builder = new Builder(httpMethodFunction.apply(builder.httpMethod), builder.uri)
					.id(builder.id)
					.headers(new HashMap<>(builder.headers))
					.cookies(new HashSet<>(builder.cookies))
					.body(builder.body);

			return this;
		}

		@Nonnull
		public Copier uri(@Nonnull Function<String, String> uriFunction) {
			requireNonNull(uriFunction);

			this.builder = new Builder(builder.httpMethod, uriFunction.apply(builder.uri))
					.id(builder.id)
					.headers(new HashMap<>(builder.headers))
					.cookies(new HashSet<>(builder.cookies))
					.body(builder.body);

			return this;
		}

		@Nonnull
		public Copier id(@Nonnull Function<Long, Long> idFunction) {
			requireNonNull(idFunction);

			builder.id = idFunction.apply(builder.id);
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
		public Request finish() {
			return this.builder.build();
		}
	}
}