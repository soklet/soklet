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

import com.soklet.core.impl.DefaultIdGenerator;
import com.soklet.core.impl.DefaultMultipartParser;
import com.soklet.internal.spring.LinkedCaseInsensitiveMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.lang.String.format;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class Request {
	@Nonnull
	private static final Charset DEFAULT_CHARSET;
	@Nonnull
	private static final IdGenerator DEFAULT_ID_GENERATOR;

	static {
		DEFAULT_CHARSET = StandardCharsets.UTF_8;
		DEFAULT_ID_GENERATOR = new DefaultIdGenerator();
	}

	@Nonnull
	private final Object id;
	@Nonnull
	private final HttpMethod httpMethod;
	@Nonnull
	private final String uri;
	@Nonnull
	private final String path;
	@Nonnull
	private final Map<String, Set<String>> cookies;
	@Nonnull
	private final Map<String, Set<String>> queryParameters;
	@Nonnull
	private final Map<String, Set<String>> formParameters;
	@Nullable
	private final String contentType;
	@Nullable
	private final Charset charset;
	@Nonnull
	private final Map<String, Set<String>> headers;
	@Nullable
	private final Cors cors;
	@Nullable
	private final byte[] body;
	@Nonnull
	private final Boolean multipart;
	@Nonnull
	private final Map<String, Set<MultipartField>> multipartFields;
	@Nonnull
	private final Boolean contentTooLarge;
	@Nonnull
	private final ReentrantLock lock;
	@Nullable
	private volatile String bodyAsString = null;
	@Nullable
	private volatile List<Locale> locales = null;

	protected Request(@Nonnull Builder builder) {
		requireNonNull(builder);

		// TODO: should we use InstanceProvider to vend IdGenerator type instead of explicitly specifying?
		IdGenerator idGenerator = builder.idGenerator == null ? getDefaultIdGenerator() : builder.idGenerator;

		this.lock = new ReentrantLock();
		this.id = builder.id == null ? idGenerator.generateId() : builder.id;
		this.httpMethod = builder.httpMethod;
		this.uri = builder.uri;
		this.path = Utilities.normalizedPathForUrl(builder.uri);
		this.queryParameters = Collections.unmodifiableMap(Utilities.extractQueryParametersFromUrl(builder.uri));

		// Header names are case-insensitive.  Enforce that here with a special map
		this.headers = Collections.unmodifiableMap(new LinkedCaseInsensitiveMap<>(builder.headers));
		this.cookies = Collections.unmodifiableMap(Utilities.extractCookiesFromHeaders(this.headers));
		this.cors = Cors.fromHeaders(this.httpMethod, this.headers).orElse(null);
		this.body = builder.body;
		this.contentType = Utilities.extractContentTypeFromHeaders(this.headers).orElse(null);
		this.charset = Utilities.extractCharsetFromHeaders(this.headers).orElse(null);

		// Form parameters
		// TODO: optimize copy/modify scenarios - we don't want to be re-processing body data
		Map<String, Set<String>> formParameters = Map.of();

		if (this.body != null && this.contentType != null && this.contentType.equalsIgnoreCase("application/x-www-form-urlencoded")) {
			String bodyAsString = getBodyAsString().orElse(null);
			formParameters = Collections.unmodifiableMap(Utilities.extractQueryParametersFromQuery(bodyAsString));
		}

		this.formParameters = formParameters;

		// Multipart handling
		// TODO: optimize copy/modify scenarios - we don't want to be copying big already-parsed multipart byte arrays
		boolean multipart = false;
		Map<String, Set<MultipartField>> multipartFields = Map.of();

		if (this.contentType != null && this.contentType.toLowerCase(Locale.ENGLISH).startsWith("multipart/")) {
			multipart = true;

			// TODO: should we use InstanceProvider to vend MultipartParser type instead of explicitly specifying?
			MultipartParser multipartParser = builder.multipartParser == null ? DefaultMultipartParser.sharedInstance() : builder.multipartParser;
			multipartFields = Collections.unmodifiableMap(multipartParser.extractMultipartFields(this));
		}

		this.multipart = multipart;
		this.multipartFields = multipartFields;

		this.contentTooLarge = builder.contentTooLarge == null ? false : builder.contentTooLarge;
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
				&& Objects.equals(getQueryParameters(), request.getQueryParameters())
				&& Objects.equals(getHeaders(), request.getHeaders())
				&& Objects.equals(getBody(), request.getBody())
				&& Objects.equals(getContentTooLarge(), request.getContentTooLarge());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getId(), getHttpMethod(), getUri(), getQueryParameters(), getHeaders(), getBody(), getContentTooLarge());
	}

	@Nonnull
	public Copier copy() {
		return new Copier(this);
	}

	@Nonnull
	public Object getId() {
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
	public Map<String, Set<String>> getCookies() {
		return this.cookies;
	}

	@Nonnull
	public Map<String, Set<String>> getQueryParameters() {
		return this.queryParameters;
	}

	@Nonnull
	public Map<String, Set<String>> getFormParameters() {
		return this.formParameters;
	}

	@Nonnull
	public Map<String, Set<String>> getHeaders() {
		return this.headers;
	}

	@Nonnull
	public Optional<String> getContentType() {
		return Optional.ofNullable(this.contentType);
	}

	@Nonnull
	public Optional<Charset> getCharset() {
		return Optional.ofNullable(this.charset);
	}

	@Nonnull
	public Boolean isMultipart() {
		return this.multipart;
	}

	@Nonnull
	public Map<String, Set<MultipartField>> getMultipartFields() {
		return this.multipartFields;
	}

	@Nonnull
	public Optional<byte[]> getBody() {
		return Optional.ofNullable(this.body);
	}

	@Nonnull
	public Boolean getContentTooLarge() {
		return this.contentTooLarge;
	}

	@Nonnull
	public Optional<String> getBodyAsString() {
		// Lazily instantiate a string instance using double-checked locking
		if (this.body != null && this.bodyAsString == null) {
			getLock().lock();
			try {
				if (this.body != null && this.bodyAsString == null)
					this.bodyAsString = new String(this.body, getCharset().orElse(DEFAULT_CHARSET));
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
	public Optional<String> getQueryParameter(@Nonnull String name) {
		requireNonNull(name);
		return singleValueForName(name, getQueryParameters());
	}

	@Nonnull
	public Optional<String> getFormParameter(@Nonnull String name) {
		requireNonNull(name);
		return singleValueForName(name, getFormParameters());
	}

	@Nonnull
	public Optional<String> getHeader(@Nonnull String name) {
		requireNonNull(name);
		return singleValueForName(name, getHeaders());
	}

	@Nonnull
	public Optional<String> getCookie(@Nonnull String name) {
		requireNonNull(name);
		return singleValueForName(name, getCookies());
	}

	@Nonnull
	public Optional<MultipartField> getMultipartField(@Nonnull String name) {
		requireNonNull(name);
		return singleValueForName(name, getMultipartFields());
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
	protected <T> Optional<T> singleValueForName(@Nonnull String name,
																							 @Nullable Map<String, Set<T>> valuesByName) {
		if (valuesByName == null)
			return Optional.empty();

		Set<T> values = valuesByName.get(name);

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
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Builder {
		@Nonnull
		private final HttpMethod httpMethod;
		@Nonnull
		private final String uri;
		@Nullable
		private Object id;
		@Nullable
		private IdGenerator idGenerator;
		@Nullable
		private MultipartParser multipartParser;
		@Nullable
		private Map<String, Set<String>> headers;
		@Nullable
		private byte[] body;
		@Nullable
		private Boolean contentTooLarge;

		public Builder(@Nonnull HttpMethod httpMethod,
									 @Nonnull String uri) {
			requireNonNull(httpMethod);
			requireNonNull(uri);

			this.httpMethod = httpMethod;
			this.uri = uri;
		}

		@Nonnull
		public Builder id(@Nullable Object id) {
			this.id = id;
			return this;
		}

		@Nonnull
		public Builder idGenerator(@Nullable IdGenerator idGenerator) {
			this.idGenerator = idGenerator;
			return this;
		}

		@Nonnull
		public Builder multipartParser(@Nullable MultipartParser multipartParser) {
			this.multipartParser = multipartParser;
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
		public Builder contentTooLarge(@Nullable Boolean contentTooLarge) {
			this.contentTooLarge = contentTooLarge;
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
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Copier {
		@Nonnull
		private Builder builder;

		Copier(@Nonnull Request request) {
			requireNonNull(request);

			this.builder = new Builder(request.getHttpMethod(), request.getUri())
					.id(request.getId())
					.headers(new LinkedCaseInsensitiveMap<>(request.getHeaders()))
					.body(request.getBody().orElse(null))
					.contentTooLarge(request.getContentTooLarge());
		}

		@Nonnull
		public Copier httpMethod(@Nonnull Function<HttpMethod, HttpMethod> httpMethodFunction) {
			requireNonNull(httpMethodFunction);

			this.builder = new Builder(httpMethodFunction.apply(builder.httpMethod), builder.uri)
					.id(builder.id)
					.headers(builder.headers == null ? null : new LinkedCaseInsensitiveMap<>(builder.headers))
					.body(builder.body)
					.contentTooLarge(builder.contentTooLarge);

			return this;
		}

		@Nonnull
		public Copier uri(@Nonnull Function<String, String> uriFunction) {
			requireNonNull(uriFunction);

			this.builder = new Builder(builder.httpMethod, uriFunction.apply(builder.uri))
					.id(builder.id)
					.headers(builder.headers == null ? null : new LinkedCaseInsensitiveMap<>(builder.headers))
					.body(builder.body)
					.contentTooLarge(builder.contentTooLarge);

			return this;
		}

		@Nonnull
		public Copier id(@Nonnull Function<Object, Object> idFunction) {
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
		public Copier body(@Nonnull Function<byte[], byte[]> bodyFunction) {
			requireNonNull(bodyFunction);

			builder.body = bodyFunction.apply(builder.body);
			return this;
		}

		@Nonnull
		public Copier contentTooLarge(@Nonnull Function<Boolean, Boolean> contentTooLargeFunction) {
			requireNonNull(contentTooLargeFunction);

			builder.contentTooLarge = contentTooLargeFunction.apply(builder.contentTooLarge);
			return this;
		}

		@Nonnull
		public Request finish() {
			return this.builder.build();
		}
	}
}
