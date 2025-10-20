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

import com.soklet.Utilities.QueryDecodingStrategy;
import com.soklet.internal.spring.LinkedCaseInsensitiveMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Locale.LanguageRange;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.soklet.Utilities.trimAggressivelyToEmpty;
import static com.soklet.Utilities.trimAggressivelyToNull;
import static java.lang.String.format;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

/**
 * Encapsulates information specified in an HTTP request.
 * <p>
 * Instances can be acquired via the {@link #with(HttpMethod, String)} builder factory method.
 * <p>
 * Detailed documentation available at <a href="https://www.soklet.com/docs/request-handling">https://www.soklet.com/docs/request-handling</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class Request {
	@Nonnull
	private static final Charset DEFAULT_CHARSET;
	@Nonnull
	private static final IdGenerator DEFAULT_ID_GENERATOR;

	static {
		DEFAULT_CHARSET = StandardCharsets.UTF_8;
		DEFAULT_ID_GENERATOR = DefaultIdGenerator.withDefaults();
	}

	@Nonnull
	private final Object id;
	@Nonnull
	private final HttpMethod httpMethod;
	@Nonnull
	private final String uri;
	@Nonnull
	private final ResourcePath resourcePath;
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
	private final CorsPreflight corsPreflight;
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
	@Nullable
	private volatile List<LanguageRange> languageRanges = null;

	/**
	 * Acquires a builder for {@link Request} instances.
	 *
	 * @param httpMethod the HTTP method for this request ({@code GET, POST, etc.})
	 * @param uri        the URI for this request, which must start with a {@code /} character and might include query parameters, e.g. {@code /example/123} or {@code /one?two=three}
	 * @return the builder
	 */
	@Nonnull
	public static Builder with(@Nonnull HttpMethod httpMethod,
														 @Nonnull String uri) {
		requireNonNull(httpMethod);
		requireNonNull(uri);

		return new Builder(httpMethod, uri);
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

	protected Request(@Nonnull Builder builder) {
		requireNonNull(builder);

		IdGenerator idGenerator = builder.idGenerator == null ? getDefaultIdGenerator() : builder.idGenerator;

		this.lock = new ReentrantLock();
		this.id = builder.id == null ? idGenerator.generateId() : builder.id;
		this.httpMethod = builder.httpMethod;

		// Header names are case-insensitive.  Enforce that here with a special map
		Map<String, Set<String>> caseInsensitiveHeaders = new LinkedCaseInsensitiveMap<>(builder.headers);
		this.headers = Collections.unmodifiableMap(caseInsensitiveHeaders);
		this.cookies = Collections.unmodifiableMap(Utilities.extractCookiesFromHeaders(caseInsensitiveHeaders));
		this.corsPreflight = this.httpMethod == HttpMethod.OPTIONS ? CorsPreflight.fromHeaders(this.headers).orElse(null) : null;
		this.cors = this.corsPreflight == null ? Cors.fromHeaders(this.httpMethod, this.headers).orElse(null) : null;
		this.body = builder.body;
		this.contentType = Utilities.extractContentTypeFromHeaders(this.headers).orElse(null);
		this.charset = Utilities.extractCharsetFromHeaders(this.headers).orElse(null);

		String uri = trimAggressivelyToNull(builder.uri);

		if (uri == null)
			throw new IllegalArgumentException("URI cannot be blank.");

		if (!uri.startsWith("/"))
			throw new IllegalArgumentException(format("URI must start with a '/' character. Illegal URI was '%s'", uri));

		// If the URI contains a query string, parse query parameters (if present) from it
		if (uri.contains("?")) {
			this.uri = uri;
			// We always assume x-www-form-urlencoded, because it's possible for browsers to submit GETs with no content-type and we just have to guess.
			// This means we decode "+" as " " and then apply any percent-decoding rules.
			// In the future, we might expose a way to let applications prefer QueryDecodingStrategy.RFC_3986_STRICT instead, which treats "+" as literal
			this.queryParameters = Collections.unmodifiableMap(Utilities.extractQueryParametersFromUrl(uri, QueryDecodingStrategy.X_WWW_FORM_URLENCODED, getCharset().orElse(DEFAULT_CHARSET)));

			// Cannot have 2 different ways of specifying query parameters
			if (builder.queryParameters != null && builder.queryParameters.size() > 0)
				throw new IllegalArgumentException("You cannot specify both query parameters and a URI with a query string.");
		} else {
			// If the URI does not contain a query string, then use query parameters provided by the builder, if present
			this.queryParameters = builder.queryParameters == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(builder.queryParameters));

			if (this.queryParameters.size() == 0) {
				this.uri = uri;
			} else {
				Charset queryParameterCharset = getCharset().orElse(DEFAULT_CHARSET);
				String queryString = this.queryParameters.entrySet().stream()
						.map((entry) -> {
							String name = entry.getKey();
							Set<String> values = entry.getValue();

							if (name == null)
								return List.<String>of();

							if (values == null || values.size() == 0)
								return List.of(format("%s=", URLEncoder.encode(name, queryParameterCharset)));

							List<String> nameValuePairs = new ArrayList<>();

							for (String value : values)
								nameValuePairs.add(format("%s=%s", URLEncoder.encode(name, queryParameterCharset),
										value == null ? "" : URLEncoder.encode(value, queryParameterCharset)));

							return nameValuePairs;
						})
						.filter(nameValuePairs -> nameValuePairs.size() > 0)
						.flatMap(Collection::stream)
						.collect(Collectors.joining("&"));

				this.uri = format("%s?%s", uri, queryString);
			}
		}

		this.resourcePath = ResourcePath.withPath(Utilities.normalizedPathForUrl(uri, true));

		// Form parameters
		// TODO: optimize copy/modify scenarios - we don't want to be re-processing body data
		Map<String, Set<String>> formParameters = Map.of();

		if (this.body != null && this.contentType != null && this.contentType.equalsIgnoreCase("application/x-www-form-urlencoded")) {
			String bodyAsString = getBodyAsString().orElse(null);
			formParameters = Collections.unmodifiableMap(Utilities.extractQueryParametersFromQuery(bodyAsString, QueryDecodingStrategy.X_WWW_FORM_URLENCODED, getCharset().orElse(DEFAULT_CHARSET)));
		}

		this.formParameters = formParameters;

		// Multipart handling
		// TODO: optimize copy/modify scenarios - we don't want to be copying big already-parsed multipart byte arrays
		boolean multipart = false;
		Map<String, Set<MultipartField>> multipartFields = Map.of();

		if (this.contentType != null && this.contentType.toLowerCase(Locale.ENGLISH).startsWith("multipart/")) {
			multipart = true;

			MultipartParser multipartParser = builder.multipartParser == null ? DefaultMultipartParser.defaultInstance() : builder.multipartParser;
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
				&& Objects.equals(isContentTooLarge(), request.isContentTooLarge());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getId(), getHttpMethod(), getUri(), getQueryParameters(), getHeaders(), getBody(), isContentTooLarge());
	}

	/**
	 * An application-specific identifier for this request.
	 * <p>
	 * The identifier is not necessarily unique (for example, numbers that "wrap around" if they get too large).
	 *
	 * @return the request's identifier
	 */
	@Nonnull
	public Object getId() {
		return this.id;
	}

	/**
	 * The <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods">HTTP method</a> for this request.
	 *
	 * @return the request's HTTP method
	 */
	@Nonnull
	public HttpMethod getHttpMethod() {
		return this.httpMethod;
	}

	/**
	 * The URI for this request, which must start with a {@code /} character and might include query parameters, such as {@code /example/123} or {@code /one?two=three}.
	 *
	 * @return the request's URI
	 */
	@Nonnull
	public String getUri() {
		return this.uri;
	}

	/**
	 * The path component of this request, which is a representation of the value returned by {@link #getUri()} with the query string (if any) removed.
	 *
	 * @return the path for this request
	 */
	@Nonnull
	public String getPath() {
		return getResourcePath().getPath();
	}

	/**
	 * Convenience method to acquire a {@link ResourcePath} representation of {@link #getPath()}.
	 *
	 * @return the resource path for this request
	 */
	@Nonnull
	public ResourcePath getResourcePath() {
		return this.resourcePath;
	}

	/**
	 * The cookies provided by the client for this request.
	 * <p>
	 * The keys are the {@code Cookie} header names and the values are {@code Cookie} header values
	 * (it is possible for a client to send multiple {@code Cookie} headers with the same name).
	 * <p>
	 * <em>Note that {@code Cookie} headers, like all request headers, have case-insensitive names per the HTTP spec.</em>
	 * <p>
	 * Use {@link #getCookie(String)} for a convenience method to access cookie values when only one is expected.
	 *
	 * @return the request's cookies
	 */
	@Nonnull
	public Map<String, Set<String>> getCookies() {
		return this.cookies;
	}

	/**
	 * The query parameters provided by the client for this request.
	 * <p>
	 * The keys are the query parameter names and the values are query parameter values
	 * (it is possible for a client to send multiple query parameters with the same name, e.g. {@code ?test=1&test=2}).
	 * <p>
	 * <em>Note that query parameters have case-sensitive names per the HTTP spec.</em>
	 * <p>
	 * Use {@link #getQueryParameter(String)} for a convenience method to access query parameter values when only one is expected.
	 *
	 * @return the request's query parameters
	 */
	@Nonnull
	public Map<String, Set<String>> getQueryParameters() {
		return this.queryParameters;
	}

	/**
	 * The HTML {@code form} parameters provided by the client for this request.
	 * <p>
	 * The keys are the form parameter names and the values are form parameter values
	 * (it is possible for a client to send multiple form parameters with the same name, e.g. {@code ?test=1&test=2}).
	 * <p>
	 * <em>Note that form parameters have case-sensitive names per the HTTP spec.</em>
	 * <p>
	 * Use {@link #getFormParameter(String)} for a convenience method to access form parameter values when only one is expected.
	 *
	 * @return the request's form parameters
	 */
	@Nonnull
	public Map<String, Set<String>> getFormParameters() {
		return this.formParameters;
	}

	/**
	 * The headers provided by the client for this request.
	 * <p>
	 * The keys are the header names and the values are header values
	 * (it is possible for a client to send multiple headers with the same name).
	 * <p>
	 * <em>Note that request headers have case-insensitive names per the HTTP spec.</em>
	 * <p>
	 * Use {@link #getHeader(String)} for a convenience method to access header values when only one is expected.
	 *
	 * @return the request's headers
	 */
	@Nonnull
	public Map<String, Set<String>> getHeaders() {
		return this.headers;
	}

	/**
	 * The {@code Content-Type} header value, as specified by the client.
	 *
	 * @return the request's {@code Content-Type} header value, or {@link Optional#empty()} if not specified
	 */
	@Nonnull
	public Optional<String> getContentType() {
		return Optional.ofNullable(this.contentType);
	}

	/**
	 * The request's character encoding, as specified by the client in the {@code Content-Type} header value.
	 *
	 * @return the request's character encoding, or {@link Optional#empty()} if not specified
	 */
	@Nonnull
	public Optional<Charset> getCharset() {
		return Optional.ofNullable(this.charset);
	}

	/**
	 * Is this a request with {@code Content-Type} of {@code multipart/form-data}?
	 *
	 * @return {@code true} if this is a {@code multipart/form-data} request, {@code false} otherwise
	 */
	@Nonnull
	public Boolean isMultipart() {
		return this.multipart;
	}

	/**
	 * The HTML {@code multipart/form-data} fields provided by the client for this request.
	 * <p>
	 * The keys are the multipart field names and the values are multipart field values
	 * (it is possible for a client to send multiple multipart fields with the same name).
	 * <p>
	 * <em>Note that multipart fields have case-sensitive names per the HTTP spec.</em>
	 * <p>
	 * Use {@link #getMultipartField(String)} for a convenience method to access a multipart parameter field value when only one is expected.
	 * <p>
	 * When using Soklet's default {@link Server}, multipart fields are parsed using the {@link MultipartParser} as configured by {@link Server.Builder#multipartParser(MultipartParser)}.
	 *
	 * @return the request's multipart fields, or the empty map if none are present
	 */
	@Nonnull
	public Map<String, Set<MultipartField>> getMultipartFields() {
		return this.multipartFields;
	}

	/**
	 * The raw bytes of the request body.
	 * <p>
	 * For convenience, {@link #getBodyAsString()} is available if you expect your request body to be of type {@link String}.
	 *
	 * @return the request body bytes, or {@link Optional#empty()} if none was supplied
	 */
	@Nonnull
	public Optional<byte[]> getBody() {
		return Optional.ofNullable(this.body);
	}

	/**
	 * Was this request too large for the server to handle?
	 * <p>
	 * <em>If so, this request might have incomplete sets of headers/cookies. It will always have a zero-length body.</em>
	 * <p>
	 * Soklet is designed to power systems that exchange small "transactional" payloads that live entirely in memory. It is not appropriate for handling multipart files at scale, buffering uploads to disk, streaming, etc.
	 * <p>
	 * When using Soklet's default {@link Server}, maximum request size is configured by {@link Server.Builder#maximumRequestSizeInBytes(Integer)}.
	 *
	 * @return {@code true} if this request is larger than the server is able to handle, {@code false} otherwise
	 */
	@Nonnull
	public Boolean isContentTooLarge() {
		return this.contentTooLarge;
	}

	/**
	 * Convenience method that provides the {@link #getBody()} bytes as a {@link String} encoded using the client-specified character set per {@link #getCharset()}.
	 * <p>
	 * If no character set is specified, {@link StandardCharsets#UTF_8} is used to perform the encoding.
	 * <p>
	 * This method will lazily convert the raw bytes as specified by {@link #getBody()} to an instance of {@link String} when first invoked.  The {@link String} representation is then cached and re-used for subsequent invocations.
	 * <p>
	 * This method is threadsafe.
	 *
	 * @return a {@link String} representation of this request's body, or {@link Optional#empty()} if no request body was specified by the client
	 */
	@Nonnull
	public Optional<String> getBodyAsString() {
		// Lazily instantiate a string instance using double-checked locking
		String result = this.bodyAsString;

		if (this.body != null && result == null) {
			getLock().lock();

			try {
				result = this.bodyAsString;

				if (this.body != null && result == null) {
					result = new String(this.body, getCharset().orElse(DEFAULT_CHARSET));
					this.bodyAsString = result;
				}
			} finally {
				getLock().unlock();
			}
		}

		return Optional.ofNullable(result);
	}

	/**
	 * <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS">Non-preflight CORS</a> request data.
	 * <p>
	 * See <a href="https://www.soklet.com/docs/cors">https://www.soklet.com/docs/cors</a> for details.
	 *
	 * @return non-preflight CORS request data, or {@link Optional#empty()} if none was specified
	 */
	@Nonnull
	public Optional<Cors> getCors() {
		return Optional.ofNullable(this.cors);
	}

	/**
	 * <a href="https://developer.mozilla.org/en-US/docs/Glossary/Preflight_request">CORS preflight</a>-related request data.
	 * <p>
	 * See <a href="https://www.soklet.com/docs/cors">https://www.soklet.com/docs/cors</a> for details.
	 *
	 * @return preflight CORS request data, or {@link Optional#empty()} if none was specified
	 */
	@Nonnull
	public Optional<CorsPreflight> getCorsPreflight() {
		return Optional.ofNullable(this.corsPreflight);
	}

	/**
	 * Locale information for this request as specified by {@code Accept-Language} header value[s] and ordered by weight as defined by <a href="https://www.rfc-editor.org/rfc/rfc7231#section-5.3.5">RFC 7231, Section 5.3.5</a>.
	 * <p>
	 * This method will lazily parse {@code Accept-Language} header values into to an ordered {@link List} of {@link Locale} when first invoked.  This representation is then cached and re-used for subsequent invocations.
	 * <p>
	 * This method is threadsafe.
	 * <p>
	 * See {@link #getLanguageRanges()} for a variant that pulls {@link LanguageRange} values.
	 *
	 * @return locale information for this request, or the empty list if none was specified
	 */
	@Nonnull
	public List<Locale> getLocales() {
		// Lazily instantiate our parsed locales using double-checked locking
		List<Locale> result = this.locales;

		if (result == null) {
			getLock().lock();

			try {
				result = this.locales;

				if (result == null) {
					Set<String> acceptLanguageHeaderValues = getHeaders().get("Accept-Language");

					if (acceptLanguageHeaderValues != null && !acceptLanguageHeaderValues.isEmpty()) {
						// Support data spread across multiple header lines, which spec allows
						String acceptLanguageHeaderValue = acceptLanguageHeaderValues.stream()
								.filter(value -> trimAggressivelyToEmpty(value).length() > 0)
								.collect(Collectors.joining(","));

						try {
							result = unmodifiableList(Utilities.extractLocalesFromAcceptLanguageHeaderValue(acceptLanguageHeaderValue));
						} catch (Exception ignored) {
							// Malformed Accept-Language header; ignore it
							result = List.of();
						}
					} else {
						result = List.of();
					}

					this.locales = result;
				}
			} finally {
				getLock().unlock();
			}
		}

		return result;
	}

	/**
	 * {@link LanguageRange} information for this request as specified by {@code Accept-Language} header value[s].
	 * <p>
	 * This method will lazily parse {@code Accept-Language} header values into to an ordered {@link List} of {@link LanguageRange} when first invoked.  This representation is then cached and re-used for subsequent invocations.
	 * <p>
	 * This method is threadsafe.
	 * <p>
	 * See {@link #getLocales()} for a variant that pulls {@link Locale} values.
	 *
	 * @return language range information for this request, or the empty list if none was specified
	 */
	@Nonnull
	public List<LanguageRange> getLanguageRanges() {
		// Lazily instantiate our parsed language ranges using double-checked locking
		List<LanguageRange> result = this.languageRanges;

		if (result == null) {
			getLock().lock();
			try {
				result = this.languageRanges;

				if (result == null) {
					Set<String> acceptLanguageHeaderValues = getHeaders().get("Accept-Language");

					if (acceptLanguageHeaderValues != null && !acceptLanguageHeaderValues.isEmpty()) {
						// Support data spread across multiple header lines, which spec allows
						String acceptLanguageHeaderValue = acceptLanguageHeaderValues.stream()
								.filter(value -> trimAggressivelyToEmpty(value).length() > 0)
								.collect(Collectors.joining(","));

						try {
							result = Collections.unmodifiableList(LanguageRange.parse(acceptLanguageHeaderValue));
						} catch (Exception ignored) {
							// Malformed Accept-Language header; ignore it
							result = List.of();
						}
					} else {
						result = List.of();
					}

					this.languageRanges = result;
				}
			} finally {
				getLock().unlock();
			}
		}

		return result;
	}

	/**
	 * Convenience method to access a query parameter's value when at most one is expected for the given {@code name}.
	 * <p>
	 * If a query parameter {@code name} can support multiple values, {@link #getQueryParameters()} should be used instead of this method.
	 * <p>
	 * If this method is invoked for a query parameter {@code name} with multiple values, Soklet does not guarantee which value will be returned.
	 * <p>
	 * <em>Note that query parameters have case-sensitive names per the HTTP spec.</em>
	 *
	 * @param name the name of the query parameter
	 * @return the value for the query parameter, or {@link Optional#empty()} if none is present
	 */
	@Nonnull
	public Optional<String> getQueryParameter(@Nonnull String name) {
		requireNonNull(name);
		return singleValueForName(name, getQueryParameters());
	}

	/**
	 * Convenience method to access a form parameter's value when at most one is expected for the given {@code name}.
	 * <p>
	 * If a form parameter {@code name} can support multiple values, {@link #getFormParameters()} should be used instead of this method.
	 * <p>
	 * If this method is invoked for a form parameter {@code name} with multiple values, Soklet does not guarantee which value will be returned.
	 * <p>
	 * <em>Note that form parameters have case-sensitive names per the HTTP spec.</em>
	 *
	 * @param name the name of the form parameter
	 * @return the value for the form parameter, or {@link Optional#empty()} if none is present
	 */
	@Nonnull
	public Optional<String> getFormParameter(@Nonnull String name) {
		requireNonNull(name);
		return singleValueForName(name, getFormParameters());
	}

	/**
	 * Convenience method to access a header's value when at most one is expected for the given {@code name}.
	 * <p>
	 * If a header {@code name} can support multiple values, {@link #getHeaders()} should be used instead of this method.
	 * <p>
	 * If this method is invoked for a header {@code name} with multiple values, Soklet does not guarantee which value will be returned.
	 * <p>
	 * <em>Note that request headers have case-insensitive names per the HTTP spec.</em>
	 *
	 * @param name the name of the header
	 * @return the value for the header, or {@link Optional#empty()} if none is present
	 */
	@Nonnull
	public Optional<String> getHeader(@Nonnull String name) {
		requireNonNull(name);
		return singleValueForName(name, getHeaders());
	}

	/**
	 * Convenience method to access a cookie's value when at most one is expected for the given {@code name}.
	 * <p>
	 * If a cookie {@code name} can support multiple values, {@link #getCookies()} should be used instead of this method.
	 * <p>
	 * If this method is invoked for a cookie {@code name} with multiple values, Soklet does not guarantee which value will be returned.
	 * <p>
	 * <em>Note that {@code Cookie} headers, like all request headers, have case-insensitive names per the HTTP spec.</em>
	 *
	 * @param name the name of the cookie
	 * @return the value for the cookie, or {@link Optional#empty()} if none is present
	 */
	@Nonnull
	public Optional<String> getCookie(@Nonnull String name) {
		requireNonNull(name);
		return singleValueForName(name, getCookies());
	}

	/**
	 * Convenience method to access a multipart field when at most one is expected for the given {@code name}.
	 * <p>
	 * If a {@code name} can support multiple multipart fields, {@link #getMultipartFields()} should be used instead of this method.
	 * <p>
	 * If this method is invoked for a {@code name} with multiple multipart field values, Soklet does not guarantee which value will be returned.
	 * <p>
	 * <em>Note that multipart fields have case-sensitive names per the HTTP spec.</em>
	 *
	 * @param name the name of the multipart field
	 * @return the multipart field value, or {@link Optional#empty()} if none is present
	 */
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
	 * Builder used to construct instances of {@link Request} via {@link Request#with(HttpMethod, String)}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@Nonnull
		private HttpMethod httpMethod;
		@Nonnull
		private String uri;
		@Nullable
		private Object id;
		@Nullable
		private IdGenerator idGenerator;
		@Nullable
		private MultipartParser multipartParser;
		@Nullable
		private Map<String, Set<String>> queryParameters;
		@Nullable
		private Map<String, Set<String>> headers;
		@Nullable
		private byte[] body;
		@Nullable
		private Boolean contentTooLarge;

		protected Builder(@Nonnull HttpMethod httpMethod,
											@Nonnull String uri) {
			requireNonNull(httpMethod);
			requireNonNull(uri);

			this.httpMethod = httpMethod;
			this.uri = uri;
		}

		@Nonnull
		public Builder httpMethod(@Nonnull HttpMethod httpMethod) {
			requireNonNull(httpMethod);
			this.httpMethod = httpMethod;
			return this;
		}

		@Nonnull
		public Builder uri(@Nonnull String uri) {
			requireNonNull(uri);
			this.uri = uri;
			return this;
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
		public Builder queryParameters(@Nullable Map<String, Set<String>> queryParameters) {
			this.queryParameters = queryParameters;
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
	 * Builder used to copy instances of {@link Request} via {@link Request#copy()}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Copier {
		@Nonnull
		private final Builder builder;

		Copier(@Nonnull Request request) {
			requireNonNull(request);

			this.builder = new Builder(request.getHttpMethod(), request.getUri())
					.id(request.getId())
					.queryParameters(new LinkedHashMap<>(request.getQueryParameters()))
					.headers(new LinkedCaseInsensitiveMap<>(request.getHeaders()))
					.body(request.getBody().orElse(null))
					.contentTooLarge(request.isContentTooLarge());
		}

		@Nonnull
		public Copier httpMethod(@Nonnull HttpMethod httpMethod) {
			requireNonNull(httpMethod);
			this.builder.httpMethod(httpMethod);
			return this;
		}

		@Nonnull
		public Copier uri(@Nonnull String uri) {
			requireNonNull(uri);
			this.builder.uri(uri);
			return this;
		}

		@Nonnull
		public Copier id(@Nullable Object id) {
			this.builder.id(id);
			return this;
		}

		@Nonnull
		public Copier queryParameters(@Nullable Map<String, Set<String>> queryParameters) {
			this.builder.queryParameters(queryParameters);
			return this;
		}

		// Convenience method for mutation
		@Nonnull
		public Copier queryParameters(@Nonnull Consumer<Map<String, Set<String>>> queryParametersConsumer) {
			requireNonNull(queryParametersConsumer);

			if (this.builder.queryParameters == null)
				this.builder.queryParameters(new LinkedHashMap<>());

			queryParametersConsumer.accept(this.builder.queryParameters);
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
		public Copier body(@Nullable byte[] body) {
			this.builder.body(body);
			return this;
		}

		@Nonnull
		public Copier contentTooLarge(@Nullable Boolean contentTooLarge) {
			this.builder.contentTooLarge(contentTooLarge);
			return this;
		}

		@Nonnull
		public Request finish() {
			return this.builder.build();
		}
	}
}
