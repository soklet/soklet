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

import com.soklet.exception.IllegalFormParameterException;
import com.soklet.exception.IllegalMultipartFieldException;
import com.soklet.exception.IllegalQueryParameterException;
import com.soklet.exception.IllegalRequestCookieException;
import com.soklet.exception.IllegalRequestException;
import com.soklet.exception.IllegalRequestHeaderException;
import com.soklet.internal.spring.LinkedCaseInsensitiveMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import static java.lang.String.format;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

/**
 * Encapsulates information specified in an HTTP request.
 * <p>
 * Instances can be acquired via the {@link #withRawUrl(HttpMethod, String)} (e.g. provided by clients on a "raw" HTTP/1.1 request line, un-decoded) and {@link #withPath(HttpMethod, String)} (e.g. manually-constructed during integration testing, understood to be already-decoded) builder factory methods.
 * <p>
 * Any necessary decoding (path, URL parameter, {@code Content-Type: application/x-www-form-urlencoded}, etc.) will be automatically performed.  Unless otherwise indicated, all accessor methods will return decoded data.
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
	private final String path;
	@Nonnull
	private final ResourcePath resourcePath;
	@Nonnull
	private final Map<String, Set<String>> queryParameters;
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
	private final Boolean contentTooLarge;
	@Nonnull
	private final MultipartParser multipartParser;
	@Nonnull
	private final IdGenerator<?> idGenerator;
	@Nonnull
	private final ReentrantLock lock;
	@Nullable
	private volatile String bodyAsString = null;
	@Nullable
	private volatile List<Locale> locales = null;
	@Nullable
	private volatile List<LanguageRange> languageRanges = null;
	@Nullable
	private volatile Map<String, Set<String>> cookies = null;
	@Nullable
	private volatile Map<String, Set<MultipartField>> multipartFields = null;
	@Nullable
	private volatile Map<String, Set<String>> formParameters = null;

	/**
	 * Acquires a builder for {@link Request} instances from the URL provided by clients on a "raw" HTTP/1.1 request line.
	 * <p>
	 * The provided {@code rawUrl} must be un-decoded and in either "path-and-query" form (i.e. starts with a {@code /} character) or an absolute URL (i.e. starts with {@code http://} or {@code https://}).
	 * It might include un-decoded query parameters, e.g. {@code https://www.example.com/one?two=thr%20ee} or {@code /one?two=thr%20ee}.  An exception to this rule is {@code OPTIONS *} requests, where the URL is the {@code *} "splat" symbol.
	 * <p>
	 * Paths will be percent-decoded at build time.
	 * <p>
	 * Query parameters are parsed and decoded at build time using RFC 3986 semantics - see {@link QueryDecodingStrategy#RFC_3986_STRICT}.
	 * <p>
	 * Request body form parameters with {@code Content-Type: application/x-www-form-urlencoded} are parsed and decoded at build time by using {@link QueryDecodingStrategy#X_WWW_FORM_URLENCODED}.
	 *
	 * @param httpMethod the HTTP method for this request ({@code GET, POST, etc.})
	 * @param rawUrl     the raw (un-decoded) URL for this request
	 * @return the builder
	 */
	@Nonnull
	public static RawBuilder withRawUrl(@Nonnull HttpMethod httpMethod,
																			@Nonnull String rawUrl) {
		requireNonNull(httpMethod);
		requireNonNull(rawUrl);

		return new RawBuilder(httpMethod, rawUrl);
	}

	/**
	 * Acquires a builder for {@link Request} instances from already-decoded path and query components - useful for manual construction, e.g. integration tests.
	 * <p>
	 * The provided {@code path} must start with the {@code /} character and already be decoded (e.g. {@code "/my path"}, not {@code "/my%20path"}).  It must not include query parameters. For {@code OPTIONS *} requests, the {@code path} must be {@code *} - the "splat" symbol.
	 * <p>
	 * Query parameters must be specified via {@link PathBuilder#queryParameters(Map)} and are assumed to be already-decoded.
	 * <p>
	 * Request body form parameters with {@code Content-Type: application/x-www-form-urlencoded} are parsed and decoded at build time by using {@link QueryDecodingStrategy#X_WWW_FORM_URLENCODED}.
	 *
	 * @param httpMethod the HTTP method for this request ({@code GET, POST, etc.})
	 * @param path       the decoded URL path for this request
	 * @return the builder
	 */
	@Nonnull
	public static PathBuilder withPath(@Nonnull HttpMethod httpMethod,
																		 @Nonnull String path) {
		requireNonNull(httpMethod);
		requireNonNull(path);

		return new PathBuilder(httpMethod, path);
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

	private Request(@Nullable RawBuilder rawBuilder,
									@Nullable PathBuilder pathBuilder) {
		// Should never occur
		if (rawBuilder == null && pathBuilder == null)
			throw new IllegalStateException(format("Neither %s nor %s were specified", RawBuilder.class.getSimpleName(), PathBuilder.class.getSimpleName()));

		IdGenerator builderIdGenerator = rawBuilder == null ? pathBuilder.idGenerator : rawBuilder.idGenerator;
		Object builderId = rawBuilder == null ? pathBuilder.id : rawBuilder.id;
		HttpMethod builderHttpMethod = rawBuilder == null ? pathBuilder.httpMethod : rawBuilder.httpMethod;
		byte[] builderBody = rawBuilder == null ? pathBuilder.body : rawBuilder.body;
		MultipartParser builderMultipartParser = rawBuilder == null ? pathBuilder.multipartParser : rawBuilder.multipartParser;
		Boolean builderContentTooLarge = rawBuilder == null ? pathBuilder.contentTooLarge : rawBuilder.contentTooLarge;
		Map<String, Set<String>> builderHeaders = rawBuilder == null ? pathBuilder.headers : rawBuilder.headers;

		if (builderHeaders == null)
			builderHeaders = Map.of();

		this.idGenerator = builderIdGenerator == null ? DEFAULT_ID_GENERATOR : builderIdGenerator;
		this.multipartParser = builderMultipartParser == null ? DefaultMultipartParser.defaultInstance() : builderMultipartParser;

		// Header names are case-insensitive.  Enforce that here with a special map
		Map<String, Set<String>> caseInsensitiveHeaders = new LinkedCaseInsensitiveMap<>(builderHeaders);
		this.headers = Collections.unmodifiableMap(caseInsensitiveHeaders);
		this.contentType = Utilities.extractContentTypeFromHeaders(this.headers).orElse(null);
		this.charset = Utilities.extractCharsetFromHeaders(this.headers).orElse(null);

		String path;

		// If we use PathBuilder, use its path directly.
		// If we use RawBuilder, parse and decode its path.
		if (pathBuilder != null) {
			path = trimAggressivelyToEmpty(pathBuilder.path);

			// Validate path
			if (!path.startsWith("/") && !path.equals("*"))
				throw new IllegalRequestException("Path must start with '/' or be '*'");

			if (path.contains("?"))
				throw new IllegalRequestException(format("Path should not contain a query string. Use %s.withPath(...).queryParameters(...) to specify query parameters as a %s.",
						Request.class.getSimpleName(), Map.class.getSimpleName()));

			// Use already-decoded query parameters as provided by the path builder
			this.queryParameters = pathBuilder.queryParameters == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(pathBuilder.queryParameters));
		} else {
			// RawBuilder scenario
			String rawUrl = trimAggressivelyToEmpty(rawBuilder.rawUrl);

			// Special handling for OPTIONS *
			if ("*".equals(rawUrl)) {
				path = "*";
				this.queryParameters = Map.of();
			} else {
				// First, parse and decode the path...
				path = Utilities.normalizedPathForUrl(rawUrl, true);

				// ...then, parse out any query parameters.
				if (rawUrl.contains("?")) {
					// We always assume RFC_3986_STRICT for query parameters because Soklet is for modern systems - HTML Form "GET" submissions are rare/legacy.
					// This means we leave "+" as "+" (not decode to " ") and then apply any percent-decoding rules.
					// In the future, we might expose a way to let applications prefer QueryDecodingStrategy.X_WWW_FORM_URLENCODED instead, which treats "+" as a space
					this.queryParameters = Collections.unmodifiableMap(Utilities.extractQueryParametersFromUrl(rawUrl, QueryDecodingStrategy.RFC_3986_STRICT, getCharset().orElse(DEFAULT_CHARSET)));
				} else {
					this.queryParameters = Map.of();
				}
			}
		}

		if (path.equals("*") && builderHttpMethod != HttpMethod.OPTIONS)
			throw new IllegalRequestException(format("Path '*' is only legal for HTTP %s", HttpMethod.OPTIONS.name()));

		if (path.contains("\u0000") || path.contains("%00"))
			throw new IllegalRequestException(format("Illegal null byte in path '%s'", path));

		this.path = path;

		this.lock = new ReentrantLock();
		this.id = builderId == null ? this.idGenerator.generateId() : builderId;
		this.httpMethod = builderHttpMethod;
		this.corsPreflight = this.httpMethod == HttpMethod.OPTIONS ? CorsPreflight.fromHeaders(this.headers).orElse(null) : null;
		this.cors = this.corsPreflight == null ? Cors.fromHeaders(this.httpMethod, this.headers).orElse(null) : null;
		this.resourcePath = this.path.equals("*") ? ResourcePath.OPTIONS_SPLAT_RESOURCE_PATH : ResourcePath.withPath(this.path);
		this.multipart = this.contentType != null && this.contentType.toLowerCase(Locale.ENGLISH).startsWith("multipart/");
		this.contentTooLarge = builderContentTooLarge == null ? false : builderContentTooLarge;

		// It's illegal to specify a body if the request is marked "content too large"
		this.body = this.contentTooLarge ? null : builderBody;

		// Cookies, form parameters, and multipart data are lazily parsed/instantiated when callers try to access them
	}

	@Override
	public String toString() {
		return format("%s{id=%s, httpMethod=%s, path=%s, cookies=%s, queryParameters=%s, headers=%s, body=%s}",
				getClass().getSimpleName(), getId(), getHttpMethod(), getPath(), getCookies(), getQueryParameters(),
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
				&& Objects.equals(getPath(), request.getPath())
				&& Objects.equals(getQueryParameters(), request.getQueryParameters())
				&& Objects.equals(getHeaders(), request.getHeaders())
				&& Arrays.equals(this.body, request.body)
				&& Objects.equals(isContentTooLarge(), request.isContentTooLarge());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getId(), getHttpMethod(), getPath(), getQueryParameters(), getHeaders(), Arrays.hashCode(this.body), isContentTooLarge());
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
	 * The percent-decoded path component of this request (no query string).
	 *
	 * @return the path for this request
	 */
	@Nonnull
	public String getPath() {
		return this.path;
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
		Map<String, Set<String>> result = this.cookies;

		if (result == null) {
			getLock().lock();

			try {
				result = this.cookies;

				if (result == null) {
					result = Collections.unmodifiableMap(Utilities.extractCookiesFromHeaders(getHeaders()));
					this.cookies = result;
				}
			} finally {
				getLock().unlock();
			}
		}

		return result;
	}

	/**
	 * The decoded query parameters provided by the client for this request.
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
	 * The decoded HTML {@code application/x-www-form-urlencoded} form parameters provided by the client for this request.
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
		Map<String, Set<String>> result = this.formParameters;

		if (result == null) {
			getLock().lock();
			try {
				result = this.formParameters;

				if (result == null) {
					if (this.body != null && this.contentType != null && this.contentType.equalsIgnoreCase("application/x-www-form-urlencoded")) {
						String bodyAsString = getBodyAsString().orElse(null);
						result = Collections.unmodifiableMap(Utilities.extractQueryParametersFromQuery(bodyAsString, QueryDecodingStrategy.X_WWW_FORM_URLENCODED, getCharset().orElse(DEFAULT_CHARSET)));
					} else {
						result = Map.of();
					}

					this.formParameters = result;
				}
			} finally {
				getLock().unlock();
			}
		}

		return result;
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
	 * The decoded HTML {@code multipart/form-data} fields provided by the client for this request.
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
		if (!isMultipart())
			return Map.of();

		Map<String, Set<MultipartField>> result = this.multipartFields;

		if (result == null) {
			getLock().lock();
			try {
				result = this.multipartFields;

				if (result == null) {
					result = Collections.unmodifiableMap(getMultipartParser().extractMultipartFields(this));
					this.multipartFields = result;
				}
			} finally {
				getLock().unlock();
			}
		}

		return result;
	}

	/**
	 * The raw bytes of the request body - <em>callers should not modify this array; it is not defensively copied for performance reasons</em>.
	 * <p>
	 * For convenience, {@link #getBodyAsString()} is available if you expect your request body to be of type {@link String}.
	 *
	 * @return the request body bytes, or {@link Optional#empty()} if none was supplied
	 */
	@Nonnull
	public Optional<byte[]> getBody() {
		return Optional.ofNullable(this.body);

		// Note: it would be nice to defensively copy, but it's inefficient
		// return Optional.ofNullable(this.body == null ? null : Arrays.copyOf(this.body, this.body.length));
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
	 * Convenience method to access a decoded query parameter's value when at most one is expected for the given {@code name}.
	 * <p>
	 * If a query parameter {@code name} can support multiple values, {@link #getQueryParameters()} should be used instead of this method.
	 * <p>
	 * If this method is invoked for a query parameter {@code name} with multiple values, Soklet will throw {@link IllegalQueryParameterException}.
	 * <p>
	 * <em>Note that query parameters have case-sensitive names per the HTTP spec.</em>
	 *
	 * @param name the name of the query parameter
	 * @return the value for the query parameter, or {@link Optional#empty()} if none is present
	 * @throws IllegalQueryParameterException if the query parameter with the given {@code name} has multiple values
	 */
	@Nonnull
	public Optional<String> getQueryParameter(@Nonnull String name) {
		requireNonNull(name);

		try {
			return singleValueForName(name, getQueryParameters());
		} catch (MultipleValuesException e) {
			@SuppressWarnings("unchecked")
			String valuesAsString = format("[%s]", ((Set<String>) e.getValues()).stream().collect(Collectors.joining(", ")));
			throw new IllegalQueryParameterException(format("Multiple values specified for query parameter '%s' (but expected single value): %s", name, valuesAsString), name, valuesAsString);
		}
	}

	/**
	 * Convenience method to access a decoded form parameter's value when at most one is expected for the given {@code name}.
	 * <p>
	 * If a form parameter {@code name} can support multiple values, {@link #getFormParameters()} should be used instead of this method.
	 * <p>
	 * If this method is invoked for a form parameter {@code name} with multiple values, Soklet will throw {@link IllegalFormParameterException}.
	 * <p>
	 * <em>Note that form parameters have case-sensitive names per the HTTP spec.</em>
	 *
	 * @param name the name of the form parameter
	 * @return the value for the form parameter, or {@link Optional#empty()} if none is present
	 * @throws IllegalFormParameterException if the form parameter with the given {@code name} has multiple values
	 */
	@Nonnull
	public Optional<String> getFormParameter(@Nonnull String name) {
		requireNonNull(name);

		try {
			return singleValueForName(name, getFormParameters());
		} catch (MultipleValuesException e) {
			@SuppressWarnings("unchecked")
			String valuesAsString = format("[%s]", ((Set<String>) e.getValues()).stream().collect(Collectors.joining(", ")));
			throw new IllegalFormParameterException(format("Multiple values specified for form parameter '%s' (but expected single value): %s", name, valuesAsString), name, valuesAsString);
		}
	}

	/**
	 * Convenience method to access a header's value when at most one is expected for the given {@code name}.
	 * <p>
	 * If a header {@code name} can support multiple values, {@link #getHeaders()} should be used instead of this method.
	 * <p>
	 * If this method is invoked for a header {@code name} with multiple values, Soklet will throw {@link IllegalRequestHeaderException}.
	 * <p>
	 * <em>Note that request headers have case-insensitive names per the HTTP spec.</em>
	 *
	 * @param name the name of the header
	 * @return the value for the header, or {@link Optional#empty()} if none is present
	 * @throws IllegalRequestHeaderException if the header with the given {@code name} has multiple values
	 */
	@Nonnull
	public Optional<String> getHeader(@Nonnull String name) {
		requireNonNull(name);

		try {
			return singleValueForName(name, getHeaders());
		} catch (MultipleValuesException e) {
			@SuppressWarnings("unchecked")
			String valuesAsString = format("[%s]", ((Set<String>) e.getValues()).stream().collect(Collectors.joining(", ")));
			throw new IllegalRequestHeaderException(format("Multiple values specified for request header '%s' (but expected single value): %s", name, valuesAsString), name, valuesAsString);
		}
	}

	/**
	 * Convenience method to access a cookie's value when at most one is expected for the given {@code name}.
	 * <p>
	 * If a cookie {@code name} can support multiple values, {@link #getCookies()} should be used instead of this method.
	 * <p>
	 * If this method is invoked for a cookie {@code name} with multiple values, Soklet will throw {@link IllegalRequestCookieException}.
	 * <p>
	 * <em>Note that {@code Cookie} headers, like all request headers, have case-insensitive names per the HTTP spec.</em>
	 *
	 * @param name the name of the cookie
	 * @return the value for the cookie, or {@link Optional#empty()} if none is present
	 * @throws IllegalRequestCookieException if the cookie with the given {@code name} has multiple values
	 */
	@Nonnull
	public Optional<String> getCookie(@Nonnull String name) {
		requireNonNull(name);

		try {
			return singleValueForName(name, getCookies());
		} catch (MultipleValuesException e) {
			@SuppressWarnings("unchecked")
			String valuesAsString = format("[%s]", ((Set<String>) e.getValues()).stream().collect(Collectors.joining(", ")));
			throw new IllegalRequestCookieException(format("Multiple values specified for request cookie '%s' (but expected single value): %s", name, valuesAsString), name, valuesAsString);
		}
	}

	/**
	 * Convenience method to access a decoded multipart field when at most one is expected for the given {@code name}.
	 * <p>
	 * If a {@code name} can support multiple multipart fields, {@link #getMultipartFields()} should be used instead of this method.
	 * <p>
	 * If this method is invoked for a {@code name} with multiple multipart field values, Soklet will throw {@link IllegalMultipartFieldException}.
	 * <p>
	 * <em>Note that multipart fields have case-sensitive names per the HTTP spec.</em>
	 *
	 * @param name the name of the multipart field
	 * @return the multipart field value, or {@link Optional#empty()} if none is present
	 * @throws IllegalMultipartFieldException if the multipart field with the given {@code name} has multiple values
	 */
	@Nonnull
	public Optional<MultipartField> getMultipartField(@Nonnull String name) {
		requireNonNull(name);

		try {
			return singleValueForName(name, getMultipartFields());
		} catch (MultipleValuesException e) {
			@SuppressWarnings("unchecked")
			MultipartField firstMultipartField = getMultipartFields().get(name).stream().findFirst().get();
			String valuesAsString = format("[%s]", e.getValues().stream()
					.map(multipartField -> multipartField.toString())
					.collect(Collectors.joining(", ")));

			throw new IllegalMultipartFieldException(format("Multiple values specified for multipart field '%s' (but expected single value): %s", name, valuesAsString), firstMultipartField);
		}
	}

	@Nonnull
	private MultipartParser getMultipartParser() {
		return this.multipartParser;
	}

	@Nonnull
	private IdGenerator<?> getIdGenerator() {
		return this.idGenerator;
	}

	@Nonnull
	private ReentrantLock getLock() {
		return this.lock;
	}

	@Nonnull
	private <T> Optional<T> singleValueForName(@Nonnull String name,
																						 @Nullable Map<String, Set<T>> valuesByName) throws MultipleValuesException {
		if (valuesByName == null)
			return Optional.empty();

		Set<T> values = valuesByName.get(name);

		if (values == null)
			return Optional.empty();

		if (values.size() > 1)
			throw new MultipleValuesException(name, values);

		return values.stream().findFirst();
	}

	@NotThreadSafe
	private static class MultipleValuesException extends Exception {
		@Nonnull
		private final String name;
		@Nonnull
		private final Set<?> values;

		private MultipleValuesException(@Nonnull String name,
																		@Nonnull Set<?> values) {
			super(format("Expected single value but found %d values for '%s': %s", values.size(), name, values));

			requireNonNull(name);
			requireNonNull(values);

			this.name = name;
			this.values = Collections.unmodifiableSet(new LinkedHashSet<>(values));
		}

		@Nonnull
		public String getName() {
			return this.name;
		}

		@Nonnull
		public Set<?> getValues() {
			return this.values;
		}
	}

	/**
	 * Builder used to construct instances of {@link Request} via {@link Request#withRawUrl(HttpMethod, String)}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class RawBuilder {
		@Nonnull
		private HttpMethod httpMethod;
		@Nonnull
		private String rawUrl;
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

		protected RawBuilder(@Nonnull HttpMethod httpMethod,
												 @Nonnull String rawUrl) {
			requireNonNull(httpMethod);
			requireNonNull(rawUrl);

			this.httpMethod = httpMethod;
			this.rawUrl = rawUrl;
		}

		@Nonnull
		public RawBuilder httpMethod(@Nonnull HttpMethod httpMethod) {
			requireNonNull(httpMethod);
			this.httpMethod = httpMethod;
			return this;
		}

		@Nonnull
		public RawBuilder rawUrl(@Nonnull String rawUrl) {
			requireNonNull(rawUrl);
			this.rawUrl = rawUrl;
			return this;
		}

		@Nonnull
		public RawBuilder id(@Nullable Object id) {
			this.id = id;
			return this;
		}

		@Nonnull
		public RawBuilder idGenerator(@Nullable IdGenerator idGenerator) {
			this.idGenerator = idGenerator;
			return this;
		}

		@Nonnull
		public RawBuilder multipartParser(@Nullable MultipartParser multipartParser) {
			this.multipartParser = multipartParser;
			return this;
		}

		@Nonnull
		public RawBuilder headers(@Nullable Map<String, Set<String>> headers) {
			this.headers = headers;
			return this;
		}

		@Nonnull
		public RawBuilder body(@Nullable byte[] body) {
			this.body = body;
			return this;
		}

		@Nonnull
		public RawBuilder contentTooLarge(@Nullable Boolean contentTooLarge) {
			this.contentTooLarge = contentTooLarge;
			return this;
		}

		@Nonnull
		public Request build() {
			return new Request(this, null);
		}
	}

	/**
	 * Builder used to construct instances of {@link Request} via {@link Request#withPath(HttpMethod, String)}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class PathBuilder {
		@Nonnull
		private HttpMethod httpMethod;
		@Nonnull
		private String path;
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

		protected PathBuilder(@Nonnull HttpMethod httpMethod,
													@Nonnull String path) {
			requireNonNull(httpMethod);
			requireNonNull(path);

			this.httpMethod = httpMethod;
			this.path = path;
		}

		@Nonnull
		public PathBuilder httpMethod(@Nonnull HttpMethod httpMethod) {
			requireNonNull(httpMethod);
			this.httpMethod = httpMethod;
			return this;
		}

		@Nonnull
		public PathBuilder path(@Nonnull String path) {
			requireNonNull(path);
			this.path = path;
			return this;
		}

		@Nonnull
		public PathBuilder id(@Nullable Object id) {
			this.id = id;
			return this;
		}

		@Nonnull
		public PathBuilder idGenerator(@Nullable IdGenerator idGenerator) {
			this.idGenerator = idGenerator;
			return this;
		}

		@Nonnull
		public PathBuilder multipartParser(@Nullable MultipartParser multipartParser) {
			this.multipartParser = multipartParser;
			return this;
		}

		@Nonnull
		public PathBuilder queryParameters(@Nullable Map<String, Set<String>> queryParameters) {
			this.queryParameters = queryParameters;
			return this;
		}

		@Nonnull
		public PathBuilder headers(@Nullable Map<String, Set<String>> headers) {
			this.headers = headers;
			return this;
		}

		@Nonnull
		public PathBuilder body(@Nullable byte[] body) {
			this.body = body;
			return this;
		}

		@Nonnull
		public PathBuilder contentTooLarge(@Nullable Boolean contentTooLarge) {
			this.contentTooLarge = contentTooLarge;
			return this;
		}

		@Nonnull
		public Request build() {
			return new Request(null, this);
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
		private final PathBuilder builder;

		Copier(@Nonnull Request request) {
			requireNonNull(request);

			this.builder = new PathBuilder(request.getHttpMethod(), request.getPath())
					.id(request.getId())
					.queryParameters(new LinkedHashMap<>(request.getQueryParameters()))
					.headers(new LinkedCaseInsensitiveMap<>(request.getHeaders()))
					.body(request.body) // Direct field access to avoid array copy
					.multipartParser(request.getMultipartParser())
					.idGenerator(request.getIdGenerator())
					.contentTooLarge(request.isContentTooLarge());
		}

		@Nonnull
		public Copier httpMethod(@Nonnull HttpMethod httpMethod) {
			requireNonNull(httpMethod);
			this.builder.httpMethod(httpMethod);
			return this;
		}

		@Nonnull
		public Copier path(@Nonnull String path) {
			requireNonNull(path);
			this.builder.path(path);
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
