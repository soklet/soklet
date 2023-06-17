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

import static com.soklet.core.Utilities.trimAggressively;
import static com.soklet.core.Utilities.trimAggressivelyToEmpty;
import static com.soklet.core.Utilities.trimAggressivelyToNull;
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
	private final Cors cors;
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
		this.cookies = builder.cookies == null ? Set.of() : Set.copyOf(builder.cookies);
		this.queryParameters = Collections.unmodifiableMap(Utilities.extractQueryParametersFromUrl(builder.uri));

		// Header names are case-insensitive.  Enforce that here with a special map
		Map<String, Set<String>> caseInsensitiveHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

		if (builder.headers != null)
			caseInsensitiveHeaders.putAll(builder.headers);

		this.headers = Collections.unmodifiableMap(caseInsensitiveHeaders);
		this.cors = Cors.fromHeaders(this.httpMethod, this.headers).orElse(null);
		this.body = builder.body;
	}

	@Override
	public String toString() {
		return format("%s{id=%s, httpMethod=%s, uri=%s, path=%s, cookies=%s, queryParameters=%s, headers=%s, body=%s}",
				getClass().getSimpleName(), getId(), getHttpMethod(), getUri(), getPath(), getCookies(), getQueryParameters(),
				getHeaders(), format("%d bytes", getBody().isPresent() ? getBody().get().length : 0));
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof Request request))
			return false;

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
	public Optional<Cors> getCors() {
		return Optional.ofNullable(this.cors);
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
				} else {
					this.locales = List.of();
				}
			} finally {
				getLock().unlock();
			}
		}

		return this.locales;
	}

	@Nonnull
	public Optional<String> getQueryParameterValue(@Nonnull String name) {
		requireNonNull(name);
		return singleValueForName(name, getQueryParameters());
	}

	@Nonnull
	public Optional<String> getHeaderValue(@Nonnull String name) {
		requireNonNull(name);
		return singleValueForName(name, getHeaders());
	}

	@Nonnull
	public Optional<HttpCookie> getCookieValue(@Nonnull String name) {
		requireNonNull(name);
		return getCookies().stream().findFirst();
	}

	@Nonnull
	protected IdGenerator getDefaultIdGenerator() {
		return DEFAULT_ID_GENERATOR;
	}

	@Nonnull
	protected ReentrantLock getLock() {
		return this.lock;
	}

	@Nonnull
	protected Optional<String> singleValueForName(@Nonnull String name,
																								@Nullable Map<String, Set<String>> valuesByName) {
		if (valuesByName == null)
			return Optional.empty();

		Set<String> values = valuesByName.get(name);

		if (values == null)
			return Optional.empty();

		if (values.size() > 1)
			throw new IllegalArgumentException(format("Expected single value but found multiple values for %s: %s", name, values));

		return values.stream().findFirst();
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
	 * Builder used to copy instances of {@link Request}.
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
					.headers(builder.headers == null ? null : new HashMap<>(builder.headers))
					.cookies(builder.cookies == null ? null : new HashSet<>(builder.cookies))
					.body(builder.body);

			return this;
		}

		@Nonnull
		public Copier uri(@Nonnull Function<String, String> uriFunction) {
			requireNonNull(uriFunction);

			this.builder = new Builder(builder.httpMethod, uriFunction.apply(builder.uri))
					.id(builder.id)
					.headers(builder.headers == null ? null : new HashMap<>(builder.headers))
					.cookies(builder.cookies == null ? null : new HashSet<>(builder.cookies))
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

	@NotThreadSafe
	public static class Cors {
		@Nonnull
		private final String origin;
		@Nullable
		private final HttpMethod accessControlRequestMethod;
		@Nonnull
		private final Set<String> accessControlRequestHeaders;
		@Nonnull
		private final Boolean preflight;

		public Cors(@Nonnull HttpMethod httpMethod,
								@Nonnull String origin) {
			this(httpMethod, origin, null, null);
		}

		public Cors(@Nonnull HttpMethod httpMethod,
								@Nonnull String origin,
								@Nullable HttpMethod accessControlRequestMethod,
								@Nullable Set<String> accessControlRequestHeaders) {
			requireNonNull(httpMethod);
			requireNonNull(origin);

			this.origin = origin;
			this.accessControlRequestMethod = accessControlRequestMethod;
			this.accessControlRequestHeaders = accessControlRequestHeaders == null ?
					Set.of() : Set.copyOf(accessControlRequestHeaders);
			this.preflight = httpMethod == HttpMethod.OPTIONS && accessControlRequestMethod != null;
		}

		@Nonnull
		public static Optional<Cors> fromHeaders(@Nonnull HttpMethod httpMethod,
																						 @Nonnull Map<String, Set<String>> headers) {
			requireNonNull(httpMethod);
			requireNonNull(headers);

			Set<String> originHeaderValues = headers.get("Origin");

			if (originHeaderValues == null || originHeaderValues.size() == 0)
				return Optional.empty();

			String originHeaderValue = trimAggressivelyToNull(originHeaderValues.stream().findFirst().orElse(null));

			if (originHeaderValue == null)
				return Optional.empty();

			Set<String> accessControlRequestMethodHeaderValues = headers.get("Access-Control-Request-Method");

			if (accessControlRequestMethodHeaderValues == null)
				accessControlRequestMethodHeaderValues = Set.of();

			List<HttpMethod> accessControlRequestMethods = accessControlRequestMethodHeaderValues.stream()
					.filter(headerValue -> {
						headerValue = trimAggressivelyToEmpty(headerValue);

						try {
							HttpMethod.valueOf(headerValue);
							return true;
						} catch (Exception ignored) {
							return false;
						}
					})
					.map((headerValue -> HttpMethod.valueOf(trimAggressively(headerValue))))
					.toList();

			Set<String> accessControlRequestHeaderValues = headers.get("Access-Control-Request-Header");

			if (accessControlRequestHeaderValues == null)
				accessControlRequestHeaderValues = Set.of();

			return Optional.of(new Cors(httpMethod, originHeaderValue,
					accessControlRequestMethods.size() > 0 ? accessControlRequestMethods.get(0) : null,
					accessControlRequestHeaderValues));
		}

		@Override
		@Nonnull
		public String toString() {
			return format("%s{origin=%s, accessControlRequestMethod=%s, accessControlRequestHeaders=%s}",
					getClass().getSimpleName(), getOrigin(), getAccessControlRequestMethod(), getAccessControlRequestHeaders());
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (this == object)
				return true;

			if (!(object instanceof Cors cors))
				return false;

			return Objects.equals(getOrigin(), cors.getOrigin())
					&& Objects.equals(getAccessControlRequestMethod(), cors.getAccessControlRequestMethod())
					&& Objects.equals(getAccessControlRequestHeaders(), cors.getAccessControlRequestHeaders());
		}

		@Override
		public int hashCode() {
			return Objects.hash(getOrigin(), getAccessControlRequestMethod(), getAccessControlRequestHeaders());
		}

		@Nonnull
		public String getOrigin() {
			return this.origin;
		}

		@Nonnull
		public Boolean isPreflight() {
			return this.preflight;
		}

		@Nonnull
		public Optional<HttpMethod> getAccessControlRequestMethod() {
			return Optional.ofNullable(this.accessControlRequestMethod);
		}

		@Nonnull
		public Set<String> getAccessControlRequestHeaders() {
			return this.accessControlRequestHeaders;
		}
	}
}
