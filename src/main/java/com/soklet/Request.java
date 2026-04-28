/*
 * Copyright 2022-2026 Revetware LLC.
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
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.spring.LinkedCaseInsensitiveMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import static com.soklet.Utilities.trimAggressivelyToNull;
import static java.lang.String.format;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

/**
 * Encapsulates information specified in an HTTP request.
 * <p>
 * Instances can be acquired via the {@link #withRawUrl(HttpMethod, String)} (e.g. provided by clients on a "raw" HTTP/1.1 request line, un-decoded) and {@link #withPath(HttpMethod, String)} (e.g. manually-constructed during integration testing, understood to be already-decoded) builder factory methods.
 * Convenience instance factories are also available via {@link #fromRawUrl(HttpMethod, String)} and {@link #fromPath(HttpMethod, String)}.
 * <p>
 * Any necessary decoding (path, URL parameter, {@code Content-Type: application/x-www-form-urlencoded}, etc.) will be automatically performed.  Unless otherwise indicated, all accessor methods will return decoded data.
 * <p>
 * For performance, collection values (headers, query parameters, form parameters, cookies, multipart fields) are shallow-copied and not defensively deep-copied. Treat returned collections as immutable.
 * <p>
 * Detailed documentation available at <a href="https://www.soklet.com/docs/request-handling">https://www.soklet.com/docs/request-handling</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class Request {
	@NonNull
	private static final Charset DEFAULT_CHARSET;
	@NonNull
	private static final IdGenerator DEFAULT_ID_GENERATOR;

	static {
		DEFAULT_CHARSET = StandardCharsets.UTF_8;
		DEFAULT_ID_GENERATOR = DefaultIdGenerator.defaultInstance();
	}

	@NonNull
	private final Object id;
	@NonNull
	private final HttpMethod httpMethod;
	@NonNull
	private final String rawPath;
	@Nullable
	private final String rawQuery;
	@NonNull
	private final String path;
	@NonNull
	private final ResourcePath resourcePath;
	@NonNull
	private final Boolean lazyQueryParameters;
	@Nullable
	private final String rawQueryForLazyParameters;
	@Nullable
	private volatile Map<@NonNull String, @NonNull Set<@NonNull String>> queryParameters;
	@Nullable
	private final String contentType;
	@Nullable
	private final Charset charset;
	@NonNull
	private final RequestHeaders headers;
	@Nullable
	private final TraceContext traceContext;
	@Nullable
	private final InetSocketAddress remoteAddress;
	@Nullable
	private final Cors cors;
	@Nullable
	private final CorsPreflight corsPreflight;
	@Nullable
	private final byte[] body;
	@NonNull
	private final Boolean multipart;
	@NonNull
	private final Boolean contentTooLarge;
	@NonNull
	private final MultipartParser multipartParser;
	@NonNull
	private final IdGenerator<?> idGenerator;
	@NonNull
	private final ReentrantLock lock;
	@Nullable
	private volatile String bodyAsString = null;
	@Nullable
	private volatile List<@NonNull Locale> locales = null;
	@Nullable
	private volatile List<@NonNull LanguageRange> languageRanges = null;
	@Nullable
	private volatile Map<@NonNull String, @NonNull Set<@NonNull String>> cookies = null;
	@Nullable
	private volatile Map<@NonNull String, @NonNull Set<@NonNull MultipartField>> multipartFields = null;
	@Nullable
	private volatile Map<@NonNull String, @NonNull Set<@NonNull String>> formParameters = null;

	/**
	 * Acquires a builder for {@link Request} instances from the URL provided by clients on a "raw" HTTP/1.1 request line.
	 * <p>
	 * The provided {@code rawUrl} must be un-decoded and in either "path-and-query" form (i.e. starts with a {@code /} character) or an absolute URL (i.e. starts with {@code http://} or {@code https://}).
	 * It might include un-decoded query parameters, e.g. {@code https://www.example.com/one?two=thr%20ee} or {@code /one?two=thr%20ee}.  An exception to this rule is {@code OPTIONS *} requests, where the URL is the {@code *} "splat" symbol.
	 * <p>
	 * Note: request targets are normalized to origin-form. For example, if a client sends an absolute-form URL like {@code http://example.com/path?query}, only the path and query components are retained.
	 * <p>
	 * Paths will be percent-decoded. Percent-encoded slashes (e.g. {@code %2F}) are rejected.
	 * Malformed percent-encoding is rejected.
	 * <p>
	 * Query parameters are parsed and decoded using RFC 3986 semantics - see {@link QueryFormat#RFC_3986_STRICT}.
	 * Query decoding always uses UTF-8, regardless of any {@code Content-Type} charset.
	 * <p>
	 * Request body form parameters with {@code Content-Type: application/x-www-form-urlencoded} are parsed and decoded by using {@link QueryFormat#X_WWW_FORM_URLENCODED}.
	 *
	 * @param httpMethod the HTTP method for this request ({@code GET, POST, etc.})
	 * @param rawUrl     the raw (un-decoded) URL for this request
	 * @return the builder
	 */
	@NonNull
	public static RawBuilder withRawUrl(@NonNull HttpMethod httpMethod,
																			@NonNull String rawUrl) {
		requireNonNull(httpMethod);
		requireNonNull(rawUrl);

		return new RawBuilder(httpMethod, rawUrl);
	}

	/**
	 * Creates a {@link Request} from a raw request target without additional customization.
	 *
	 * @param httpMethod the HTTP method
	 * @param rawUrl     a raw HTTP/1.1 request target (not URL-decoded)
	 * @return a {@link Request} instance
	 */
	@NonNull
	public static Request fromRawUrl(@NonNull HttpMethod httpMethod,
																	 @NonNull String rawUrl) {
		return withRawUrl(httpMethod, rawUrl).build();
	}

	/**
	 * Acquires a builder for {@link Request} instances from already-decoded path and query components - useful for manual construction, e.g. integration tests.
	 * <p>
	 * The provided {@code path} must start with the {@code /} character and already be decoded (e.g. {@code "/my path"}, not {@code "/my%20path"}).  It must not include query parameters. For {@code OPTIONS *} requests, the {@code path} must be {@code *} - the "splat" symbol.
	 * <p>
	 * Query parameters must be specified via {@link PathBuilder#queryParameters(Map)} and are assumed to be already-decoded.
	 * <p>
	 * Request body form parameters with {@code Content-Type: application/x-www-form-urlencoded} are parsed and decoded by using {@link QueryFormat#X_WWW_FORM_URLENCODED}.
	 *
	 * @param httpMethod the HTTP method for this request ({@code GET, POST, etc.})
	 * @param path       the decoded URL path for this request
	 * @return the builder
	 */
	@NonNull
	public static PathBuilder withPath(@NonNull HttpMethod httpMethod,
																		 @NonNull String path) {
		requireNonNull(httpMethod);
		requireNonNull(path);

		return new PathBuilder(httpMethod, path);
	}

	/**
	 * Creates a {@link Request} from a path without additional customization.
	 *
	 * @param httpMethod the HTTP method
	 * @param path       a decoded request path (e.g. {@code /widgets/123})
	 * @return a {@link Request} instance
	 */
	@NonNull
	public static Request fromPath(@NonNull HttpMethod httpMethod,
																 @NonNull String path) {
		return withPath(httpMethod, path).build();
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
		RequestHeaders builderHeaders = rawBuilder == null ? new MapRequestHeaders(pathBuilder.headers) : rawBuilder.requestHeaders();
		InetSocketAddress builderRemoteAddress = rawBuilder == null ? pathBuilder.remoteAddress : rawBuilder.remoteAddress;
		Boolean builderTraceContextSpecified = rawBuilder == null ? pathBuilder.traceContextSpecified : rawBuilder.traceContextSpecified;
		TraceContext builderTraceContext = rawBuilder == null ? pathBuilder.traceContext : rawBuilder.traceContext;

		this.idGenerator = builderIdGenerator == null ? DEFAULT_ID_GENERATOR : builderIdGenerator;
		this.multipartParser = builderMultipartParser == null ? DefaultMultipartParser.defaultInstance() : builderMultipartParser;

		this.headers = builderHeaders;
		this.traceContext = builderTraceContextSpecified ? builderTraceContext : extractTraceContext(builderHeaders).orElse(null);
		String contentTypeHeaderValue = firstHeaderValue(this.headers, "Content-Type").orElse(null);
		this.contentType = Utilities.extractContentTypeFromHeaderValue(contentTypeHeaderValue).orElse(null);
		this.charset = Utilities.extractCharsetFromHeaderValue(contentTypeHeaderValue).orElse(null);
		this.remoteAddress = builderRemoteAddress;

		String path;
		String rawBuilderRawQuery = null;
		String rawQueryForLazyParameters = null;
		Boolean lazyQueryParameters = false;
		Map<String, Set<String>> initialQueryParameters;

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
			initialQueryParameters = pathBuilder.queryParameters == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(pathBuilder.queryParameters));
		} else {
			// RawBuilder scenario
			String rawUrl = trimAggressivelyToEmpty(rawBuilder.rawUrl);

			// Special handling for OPTIONS *
			if ("*".equals(rawUrl)) {
				path = "*";
				initialQueryParameters = Map.of();
			} else {
				// First, parse and decode the path...
				path = Utilities.extractPathFromUrl(rawUrl, true);

				// ...then, retain raw query parameters for lazy decoding.
				rawBuilderRawQuery = rawUrl.contains("?") ? Utilities.extractRawQueryFromUrlStrict(rawUrl).orElse(null) : null;
				if (rawBuilderRawQuery != null) {
					// We always assume RFC_3986_STRICT for query parameters because Soklet is for modern systems - HTML Form "GET" submissions are rare/legacy.
					// This means we leave "+" as "+" (not decode to " ") and then apply any percent-decoding rules.
					// Query parameters are decoded as UTF-8 regardless of Content-Type.
					// In the future, we might expose a way to let applications prefer QueryFormat.X_WWW_FORM_URLENCODED instead, which treats "+" as a space
					Utilities.validatePercentEncodingInUrlComponent(rawBuilderRawQuery);
					initialQueryParameters = null;
					lazyQueryParameters = true;
					rawQueryForLazyParameters = rawBuilderRawQuery;
				} else {
					initialQueryParameters = Map.of();
				}
			}
		}

		if (path.equals("*") && builderHttpMethod != HttpMethod.OPTIONS)
			throw new IllegalRequestException(format("Path '*' is only legal for HTTP %s", HttpMethod.OPTIONS.name()));

		if (path.contains("\u0000") || path.contains("%00"))
			throw new IllegalRequestException(format("Illegal null byte in path '%s'", path));

		this.path = path;

		String rawPath;
		String rawQuery;

		if (pathBuilder != null) {
			// PathBuilder scenario: check if explicit raw values were provided
			if (pathBuilder.rawPath != null) {
				// Explicit raw values provided (e.g. from Copier preserving originals)
				rawPath = pathBuilder.rawPath;
				rawQuery = pathBuilder.rawQuery;
			} else {
				// No explicit raw values; encode from decoded values
				if (path.equals("*")) {
					rawPath = "*";
				} else {
					rawPath = Utilities.encodePath(path);
				}

				if (initialQueryParameters.isEmpty()) {
					rawQuery = null;
				} else {
					rawQuery = Utilities.encodeQueryParameters(initialQueryParameters, QueryFormat.RFC_3986_STRICT);
				}
			}
		} else {
			// RawBuilder scenario: extract raw components from rawUrl
			String rawUrl = trimAggressivelyToEmpty(rawBuilder.rawUrl);

			if ("*".equals(rawUrl)) {
				rawPath = "*";
				rawQuery = null;
			} else {
				rawPath = Utilities.extractPathFromUrl(rawUrl, false);
				if (containsEncodedSlash(rawPath))
					throw new IllegalRequestException("Encoded slashes are not allowed in request paths");
				rawQuery = rawBuilderRawQuery;
			}
		}

		this.rawPath = rawPath;
		this.rawQuery = rawQuery;
		this.queryParameters = initialQueryParameters;
		this.lazyQueryParameters = lazyQueryParameters;
		this.rawQueryForLazyParameters = rawQueryForLazyParameters;

		this.lock = new ReentrantLock();
		this.httpMethod = builderHttpMethod;
		this.corsPreflight = this.httpMethod == HttpMethod.OPTIONS ? extractCorsPreflight(this.headers).orElse(null) : null;
		this.cors = this.corsPreflight == null ? extractCors(this.httpMethod, this.headers).orElse(null) : null;
		this.resourcePath = this.path.equals("*") ? ResourcePath.OPTIONS_SPLAT_RESOURCE_PATH : ResourcePath.fromPath(this.path);
		this.multipart = this.contentType != null && this.contentType.toLowerCase(Locale.ENGLISH).startsWith("multipart/");
		this.contentTooLarge = builderContentTooLarge == null ? false : builderContentTooLarge;

		// It's illegal to specify a body if the request is marked "content too large"
		this.body = this.contentTooLarge ? null : builderBody;

		// Last step of ctor: generate an ID (if necessary) using this fully-constructed Request
		this.id = builderId == null ? this.idGenerator.generateId(this) : builderId;

		// Note that cookies, form parameters, and multipart data are lazily parsed/instantiated when callers try to access them
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
				&& Objects.equals(getTraceContext(), request.getTraceContext())
				&& Arrays.equals(this.body, request.body)
				&& Objects.equals(isContentTooLarge(), request.isContentTooLarge());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getId(), getHttpMethod(), getPath(), getQueryParameters(), getHeaders(), getTraceContext(), Arrays.hashCode(this.body), isContentTooLarge());
	}

	private static boolean containsEncodedSlash(@NonNull String rawPath) {
		requireNonNull(rawPath);
		return rawPath.toLowerCase(Locale.ROOT).contains("%2f");
	}

	/**
	 * An application-specific identifier for this request.
	 * <p>
	 * The identifier is not necessarily unique (for example, numbers that "wrap around" if they get too large).
	 *
	 * @return the request's identifier
	 */
	@NonNull
	public Object getId() {
		return this.id;
	}

	/**
	 * The <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods">HTTP method</a> for this request.
	 *
	 * @return the request's HTTP method
	 */
	@NonNull
	public HttpMethod getHttpMethod() {
		return this.httpMethod;
	}

	/**
	 * The percent-decoded path component of this request (no query string).
	 *
	 * @return the path for this request
	 */
	@NonNull
	public String getPath() {
		return this.path;
	}

	/**
	 * Convenience method to acquire a {@link ResourcePath} representation of {@link #getPath()}.
	 *
	 * @return the resource path for this request
	 */
	@NonNull
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
	@NonNull
	public Map<@NonNull String, @NonNull Set<@NonNull String>> getCookies() {
		Map<String, Set<String>> result = this.cookies;

		if (result == null) {
			getLock().lock();

			try {
				result = this.cookies;

				if (result == null) {
					Set<String> cookieHeaderValues = getHeaderValues("Cookie").orElse(Set.of());
					result = cookieHeaderValues.isEmpty()
							? Map.of()
							: Collections.unmodifiableMap(Utilities.extractCookiesFromHeaders(Map.of("Cookie", cookieHeaderValues)));
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
	@NonNull
	public Map<@NonNull String, @NonNull Set<@NonNull String>> getQueryParameters() {
		Map<String, Set<String>> result = this.queryParameters;

		if (result == null && this.lazyQueryParameters) {
			getLock().lock();

			try {
				result = this.queryParameters;

				if (result == null) {
					result = Collections.unmodifiableMap(Utilities.extractQueryParametersFromQuery(requireNonNull(this.rawQueryForLazyParameters), QueryFormat.RFC_3986_STRICT, DEFAULT_CHARSET));
					this.queryParameters = result;
				}
			} finally {
				getLock().unlock();
			}
		}

		return result == null ? Map.of() : result;
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
	@NonNull
	public Map<@NonNull String, @NonNull Set<@NonNull String>> getFormParameters() {
		Map<String, Set<String>> result = this.formParameters;

		if (result == null) {
			getLock().lock();
			try {
				result = this.formParameters;

				if (result == null) {
					if (this.body != null && this.contentType != null && this.contentType.equalsIgnoreCase("application/x-www-form-urlencoded")) {
						String bodyAsString = getBodyAsString().orElse(null);
						result = Collections.unmodifiableMap(Utilities.extractQueryParametersFromQuery(bodyAsString, QueryFormat.X_WWW_FORM_URLENCODED, getCharset().orElse(DEFAULT_CHARSET)));
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
	 * The raw (un-decoded) path component of this request exactly as the client specified.
	 * <p>
	 * For example, {@code "/a%20b"} (never decoded).
	 * <p>
	 * <em>Note: For requests constructed via {@link #withPath(HttpMethod, String)}, this value is
	 * generated by encoding the decoded path, which may not exactly match the original wire format.</em>
	 *
	 * @return the raw path for this request
	 */
	@NonNull
	public String getRawPath() {
		return this.rawPath;
	}

	/**
	 * The raw (un-decoded) query component of this request exactly as the client specified.
	 * <p>
	 * For example, {@code "a=b&c=d+e"} (never decoded).
	 * <p>
	 * This is useful for special cases like HMAC signature verification, which relies on the exact client format.
	 * <p>
	 * <em>Note: For requests constructed via {@link #withPath(HttpMethod, String)}, this value is
	 * generated by encoding the decoded query parameters, which may not exactly match the original wire format.</em>
	 *
	 * @return the raw query for this request, or {@link Optional#empty()} if none was specified
	 */
	@NonNull
	public Optional<String> getRawQuery() {
		return Optional.ofNullable(this.rawQuery);
	}

	/**
	 * The raw (un-decoded) path and query components of this request exactly as the client specified.
	 * <p>
	 * For example, {@code "/my%20path?a=b&c=d%20e"} (never decoded).
	 * <p>
	 * <em>Note: For requests constructed via {@link #withPath(HttpMethod, String)}, this value is
	 * generated by encoding the decoded path and query parameters, which may not exactly match the original wire format.</em>
	 *
	 * @return the raw path and query for this request
	 */
	@NonNull
	public String getRawPathAndQuery() {
		if (this.rawQuery == null)
			return this.rawPath;

		return this.rawPath + "?" + this.rawQuery;
	}

	/**
	 * The remote network address for the client connection, if available.
	 *
	 * @return the remote address for this request, or {@link Optional#empty()} if unavailable
	 */
	@NonNull
	public Optional<InetSocketAddress> getRemoteAddress() {
		return Optional.ofNullable(this.remoteAddress);
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
	@NonNull
	public Map<@NonNull String, @NonNull Set<@NonNull String>> getHeaders() {
		return this.headers.asMap();
	}

	/**
	 * The W3C trace context for this request, if one was supplied by the client or explicitly specified.
	 *
	 * @return the trace context, or {@link Optional#empty()} if unavailable
	 */
	@NonNull
	public Optional<TraceContext> getTraceContext() {
		return Optional.ofNullable(this.traceContext);
	}

	/**
	 * The {@code Content-Type} header value, as specified by the client.
	 *
	 * @return the request's {@code Content-Type} header value, or {@link Optional#empty()} if not specified
	 */
	@NonNull
	public Optional<String> getContentType() {
		return Optional.ofNullable(this.contentType);
	}

	/**
	 * The request's character encoding, as specified by the client in the {@code Content-Type} header value.
	 *
	 * @return the request's character encoding, or {@link Optional#empty()} if not specified
	 */
	@NonNull
	public Optional<Charset> getCharset() {
		return Optional.ofNullable(this.charset);
	}

	/**
	 * Is this a request with {@code Content-Type} of {@code multipart/form-data}?
	 *
	 * @return {@code true} if this is a {@code multipart/form-data} request, {@code false} otherwise
	 */
	@NonNull
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
	 * When using Soklet's default {@link HttpServer}, multipart fields are parsed using the {@link MultipartParser} as configured by {@link HttpServer.Builder#multipartParser(MultipartParser)}.
	 *
	 * @return the request's multipart fields, or the empty map if none are present
	 */
	@NonNull
	public Map<@NonNull String, @NonNull Set<@NonNull MultipartField>> getMultipartFields() {
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
	 * The raw bytes of the request body - <strong>callers should not modify this array; it is not defensively copied for performance reasons</strong>.
	 * <p>
	 * For convenience, {@link #getBodyAsString()} is available if you expect your request body to be of type {@link String}.
	 *
	 * @return the request body bytes, or {@link Optional#empty()} if none was supplied
	 */
	@NonNull
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
	 * When using Soklet's default {@link HttpServer}, maximum request size is configured by {@link HttpServer.Builder#maximumRequestSizeInBytes(Integer)}.
	 * That limit applies to the whole received HTTP request, including request line, headers, transfer framing, and body bytes.
	 * Applications that think in terms of payload size should leave room for request metadata and protocol framing.
	 *
	 * @return {@code true} if this request is larger than the server is able to handle, {@code false} otherwise
	 */
	@NonNull
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
	@NonNull
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
	@NonNull
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
	@NonNull
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
	@NonNull
	public List<@NonNull Locale> getLocales() {
		// Lazily instantiate our parsed locales using double-checked locking
		List<Locale> result = this.locales;

		if (result == null) {
			getLock().lock();

			try {
				result = this.locales;

				if (result == null) {
					Set<String> acceptLanguageHeaderValues = getHeaderValues("Accept-Language").orElse(null);

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
	@NonNull
	public List<@NonNull LanguageRange> getLanguageRanges() {
		// Lazily instantiate our parsed language ranges using double-checked locking
		List<LanguageRange> result = this.languageRanges;

		if (result == null) {
			getLock().lock();
			try {
				result = this.languageRanges;

				if (result == null) {
					Set<String> acceptLanguageHeaderValues = getHeaderValues("Accept-Language").orElse(null);

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
	@NonNull
	public Optional<String> getQueryParameter(@NonNull String name) {
		requireNonNull(name);

		try {
			Map<String, Set<String>> queryParameters = this.queryParameters;

			if (queryParameters == null && this.lazyQueryParameters)
				return singleValueForName(name, Utilities.extractQueryParameterValuesFromQuery(requireNonNull(this.rawQueryForLazyParameters), name, QueryFormat.RFC_3986_STRICT, DEFAULT_CHARSET).orElse(null));

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
	@NonNull
	public Optional<String> getFormParameter(@NonNull String name) {
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
	@NonNull
	public Optional<String> getHeader(@NonNull String name) {
		requireNonNull(name);

		try {
			return singleValueForName(name, getHeaderValues(name).orElse(null));
		} catch (MultipleValuesException e) {
			@SuppressWarnings("unchecked")
			String valuesAsString = format("[%s]", ((Set<String>) e.getValues()).stream().collect(Collectors.joining(", ")));
			throw new IllegalRequestHeaderException(format("Multiple values specified for request header '%s' (but expected single value): %s", name, valuesAsString), name, valuesAsString);
		}
	}

	@NonNull
	Optional<Set<@NonNull String>> getHeaderValues(@NonNull String name) {
		requireNonNull(name);
		return this.headers.get(name);
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
	@NonNull
	public Optional<String> getCookie(@NonNull String name) {
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
	@NonNull
	public Optional<MultipartField> getMultipartField(@NonNull String name) {
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

	@NonNull
	private MultipartParser getMultipartParser() {
		return this.multipartParser;
	}

	@NonNull
	private IdGenerator<?> getIdGenerator() {
		return this.idGenerator;
	}

	@NonNull
	private ReentrantLock getLock() {
		return this.lock;
	}

	@NonNull
	private <T> Optional<T> singleValueForName(@NonNull String name,
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

	@NonNull
	private <T> Optional<T> singleValueForName(@NonNull String name,
																						 @Nullable Set<T> values) throws MultipleValuesException {
		requireNonNull(name);

		if (values == null)
			return Optional.empty();

		if (values.size() > 1)
			throw new MultipleValuesException(name, values);

		return values.stream().findFirst();
	}

	@NonNull
	private static Optional<Cors> extractCors(@NonNull HttpMethod httpMethod,
																						@NonNull RequestHeaders headers) {
		requireNonNull(httpMethod);
		requireNonNull(headers);

		return firstHeaderValue(headers, "Origin").map(origin -> Cors.fromOrigin(httpMethod, origin));
	}

	@NonNull
	private static Optional<CorsPreflight> extractCorsPreflight(@NonNull RequestHeaders headers) {
		requireNonNull(headers);

		String origin = firstHeaderValue(headers, "Origin").orElse(null);

		if (origin == null)
			return Optional.empty();

		Set<String> accessControlRequestMethodHeaderValues = headers.get("Access-Control-Request-Method").orElse(Set.of());
		HttpMethod accessControlRequestMethod = null;

		for (String headerValue : accessControlRequestMethodHeaderValues) {
			headerValue = trimAggressivelyToEmpty(headerValue);

			try {
				accessControlRequestMethod = HttpMethod.valueOf(headerValue);
				break;
			} catch (Exception ignored) {
				// Ignore invalid method values.
			}
		}

		if (accessControlRequestMethod == null)
			return Optional.empty();

		Set<String> accessControlRequestHeaders = headers.get("Access-Control-Request-Headers").orElse(Set.of())
				.stream()
				.flatMap(value -> Arrays.stream(value.split(",")))
				.map(Utilities::trimAggressivelyToEmpty)
				.filter(value -> !value.isEmpty())
				.collect(Collectors.toCollection(LinkedHashSet::new));

		return Optional.of(CorsPreflight.with(origin, accessControlRequestMethod, accessControlRequestHeaders));
	}

	@NonNull
	private static Optional<String> firstHeaderValue(@NonNull RequestHeaders headers,
																									@NonNull String name) {
		requireNonNull(headers);
		requireNonNull(name);

		Set<String> values = headers.get(name).orElse(null);

		if (values == null || values.isEmpty())
			return Optional.empty();

		return Optional.ofNullable(trimAggressivelyToNull(values.stream().findFirst().orElse(null)));
	}

	@NonNull
	private static Optional<TraceContext> extractTraceContext(@NonNull RequestHeaders headers) {
		requireNonNull(headers);

		// Physical request headers preserve duplicate traceparent values. Map-backed request construction
		// uses Set values, so identical duplicates are already collapsed by the time parsing runs.
		return TraceContext.fromHeaderValues(headers.values("traceparent"), headers.values("tracestate"));
	}

	private interface RequestHeaders {
		@NonNull
		Optional<Set<@NonNull String>> get(@NonNull String name);

		@NonNull
		List<@NonNull String> values(@NonNull String name);

		@NonNull
		Map<@NonNull String, @NonNull Set<@NonNull String>> asMap();
	}

	@ThreadSafe
	private static final class MapRequestHeaders implements RequestHeaders {
		@NonNull
		private final Map<@NonNull String, @NonNull Set<@NonNull String>> headers;

		private MapRequestHeaders(@Nullable Map<@NonNull String, @NonNull Set<@NonNull String>> headers) {
			if (headers == null || headers.isEmpty()) {
				this.headers = Map.of();
			} else {
				this.headers = Collections.unmodifiableMap(new LinkedCaseInsensitiveMap<>(headers));
			}
		}

		@Override
		@NonNull
		public Optional<Set<@NonNull String>> get(@NonNull String name) {
			requireNonNull(name);
			return Optional.ofNullable(this.headers.get(name));
		}

		@Override
		@NonNull
		public List<@NonNull String> values(@NonNull String name) {
			requireNonNull(name);

			Set<String> values = this.headers.get(name);

			if (values == null || values.isEmpty())
				return List.of();

			return List.copyOf(values);
		}

		@Override
		@NonNull
		public Map<@NonNull String, @NonNull Set<@NonNull String>> asMap() {
			return this.headers;
		}
	}

	@ThreadSafe
	private static final class MicrohttpRequestHeaders implements RequestHeaders {
		@NonNull
		private final List<@NonNull Header> headers;
		@Nullable
		private volatile Map<@NonNull String, @NonNull Set<@NonNull String>> materializedHeaders;

		private MicrohttpRequestHeaders(@Nullable List<@NonNull Header> headers) {
			this.headers = headers == null ? List.of() : headers;
		}

		@Override
		@NonNull
		public Optional<Set<@NonNull String>> get(@NonNull String name) {
			requireNonNull(name);

			Set<String> matchingValues = null;

			for (Header header : this.headers) {
				if (header == null || !name.equalsIgnoreCase(trimAggressivelyToEmpty(header.name())))
					continue;

				if (matchingValues == null)
					matchingValues = new LinkedHashSet<>();

				Utilities.addParsedHeaderValues(matchingValues, header.name(), header.value());
			}

			if (matchingValues == null || matchingValues.isEmpty())
				return Optional.empty();

			return Optional.of(Collections.unmodifiableSet(matchingValues));
		}

		@Override
		@NonNull
		public List<@NonNull String> values(@NonNull String name) {
			requireNonNull(name);

			List<String> matchingValues = null;

			for (Header header : this.headers) {
				if (header == null || !name.equalsIgnoreCase(trimAggressivelyToEmpty(header.name())))
					continue;

				if (matchingValues == null)
					matchingValues = new ArrayList<>();

				matchingValues.add(trimAggressivelyToEmpty(header.value()));
			}

			return matchingValues == null || matchingValues.isEmpty()
					? List.of()
					: Collections.unmodifiableList(matchingValues);
		}

		@Override
		@NonNull
		public Map<@NonNull String, @NonNull Set<@NonNull String>> asMap() {
			Map<String, Set<String>> result = this.materializedHeaders;

			if (result == null) {
				Map<String, Set<String>> headers = new LinkedCaseInsensitiveMap<>();

				for (Header header : this.headers) {
					if (header == null)
						continue;

					Utilities.addParsedHeader(headers, header.name(), header.value());
				}

				Utilities.freezeStringValueSets(headers);
				result = Collections.unmodifiableMap(headers);
				this.materializedHeaders = result;
			}

			return result;
		}
	}

	@NotThreadSafe
	private static class MultipleValuesException extends Exception {
		@NonNull
		private final String name;
		@NonNull
		private final Set<?> values;

		private MultipleValuesException(@NonNull String name,
																		@NonNull Set<?> values) {
			super(format("Expected single value but found %d values for '%s': %s", values.size(), name, values));

			requireNonNull(name);
			requireNonNull(values);

			this.name = name;
			this.values = Collections.unmodifiableSet(new LinkedHashSet<>(values));
		}

		@NonNull
		public String getName() {
			return this.name;
		}

		@NonNull
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
		@NonNull
		private HttpMethod httpMethod;
		@NonNull
		private String rawUrl;
		@Nullable
		private Object id;
		@Nullable
		private IdGenerator idGenerator;
		@Nullable
		private MultipartParser multipartParser;
		@Nullable
		private Map<@NonNull String, @NonNull Set<@NonNull String>> headers;
		@Nullable
		private List<@NonNull Header> microhttpHeaders;
		@Nullable
		private TraceContext traceContext;
		@NonNull
		private Boolean traceContextSpecified = false;
		@Nullable
		private InetSocketAddress remoteAddress;
		@Nullable
		private byte[] body;
		@Nullable
		private Boolean contentTooLarge;

		protected RawBuilder(@NonNull HttpMethod httpMethod,
												 @NonNull String rawUrl) {
			requireNonNull(httpMethod);
			requireNonNull(rawUrl);

			this.httpMethod = httpMethod;
			this.rawUrl = rawUrl;
		}

		@NonNull
		public RawBuilder httpMethod(@NonNull HttpMethod httpMethod) {
			requireNonNull(httpMethod);
			this.httpMethod = httpMethod;
			return this;
		}

		@NonNull
		public RawBuilder rawUrl(@NonNull String rawUrl) {
			requireNonNull(rawUrl);
			this.rawUrl = rawUrl;
			return this;
		}

		@NonNull
		public RawBuilder id(@Nullable Object id) {
			this.id = id;
			return this;
		}

		@NonNull
		public RawBuilder idGenerator(@Nullable IdGenerator idGenerator) {
			this.idGenerator = idGenerator;
			return this;
		}

		@NonNull
		public RawBuilder multipartParser(@Nullable MultipartParser multipartParser) {
			this.multipartParser = multipartParser;
			return this;
		}

		@NonNull
		public RawBuilder headers(@Nullable Map<@NonNull String, @NonNull Set<@NonNull String>> headers) {
			this.headers = headers;
			this.microhttpHeaders = null;
			return this;
		}

		@NonNull
		public RawBuilder traceContext(@Nullable TraceContext traceContext) {
			this.traceContext = traceContext;
			this.traceContextSpecified = true;
			return this;
		}

		@NonNull
		RawBuilder microhttpHeaders(@Nullable List<@NonNull Header> headers) {
			this.headers = null;
			this.microhttpHeaders = headers;
			return this;
		}

		@NonNull
		public RawBuilder remoteAddress(@Nullable InetSocketAddress remoteAddress) {
			this.remoteAddress = remoteAddress;
			return this;
		}

		@NonNull
		public RawBuilder body(@Nullable byte[] body) {
			this.body = body;
			return this;
		}

		@NonNull
		public RawBuilder contentTooLarge(@Nullable Boolean contentTooLarge) {
			this.contentTooLarge = contentTooLarge;
			return this;
		}

		@NonNull
		public Request build() {
			return new Request(this, null);
		}

		@NonNull
		private RequestHeaders requestHeaders() {
			if (this.microhttpHeaders != null)
				return new MicrohttpRequestHeaders(this.microhttpHeaders);

			return new MapRequestHeaders(this.headers);
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
		@NonNull
		private HttpMethod httpMethod;
		@NonNull
		private String path;
		@Nullable
		private String rawPath;
		@Nullable
		private String rawQuery;
		@Nullable
		private Object id;
		@Nullable
		private IdGenerator idGenerator;
		@Nullable
		private MultipartParser multipartParser;
		@Nullable
		private Map<@NonNull String, @NonNull Set<@NonNull String>> queryParameters;
		@Nullable
		private Map<@NonNull String, @NonNull Set<@NonNull String>> headers;
		@Nullable
		private TraceContext traceContext;
		@NonNull
		private Boolean traceContextSpecified = false;
		@Nullable
		private InetSocketAddress remoteAddress;
		@Nullable
		private byte[] body;
		@Nullable
		private Boolean contentTooLarge;

		protected PathBuilder(@NonNull HttpMethod httpMethod,
													@NonNull String path) {
			requireNonNull(httpMethod);
			requireNonNull(path);

			this.httpMethod = httpMethod;
			this.path = path;
		}

		@NonNull
		public PathBuilder httpMethod(@NonNull HttpMethod httpMethod) {
			requireNonNull(httpMethod);
			this.httpMethod = httpMethod;
			return this;
		}

		@NonNull
		public PathBuilder path(@NonNull String path) {
			requireNonNull(path);
			this.path = path;
			return this;
		}

		// Package-private setter for raw value (used by Copier)
		@NonNull
		PathBuilder rawPath(@Nullable String rawPath) {
			this.rawPath = rawPath;
			return this;
		}

		// Package-private setter for raw value (used by Copier)
		@NonNull
		PathBuilder rawQuery(@Nullable String rawQuery) {
			this.rawQuery = rawQuery;
			return this;
		}

		@NonNull
		public PathBuilder id(@Nullable Object id) {
			this.id = id;
			return this;
		}

		@NonNull
		public PathBuilder idGenerator(@Nullable IdGenerator idGenerator) {
			this.idGenerator = idGenerator;
			return this;
		}

		@NonNull
		public PathBuilder multipartParser(@Nullable MultipartParser multipartParser) {
			this.multipartParser = multipartParser;
			return this;
		}

		@NonNull
		public PathBuilder queryParameters(@Nullable Map<@NonNull String, @NonNull Set<@NonNull String>> queryParameters) {
			this.queryParameters = queryParameters;
			return this;
		}

		@NonNull
		public PathBuilder headers(@Nullable Map<@NonNull String, @NonNull Set<@NonNull String>> headers) {
			this.headers = headers;
			return this;
		}

		@NonNull
		public PathBuilder traceContext(@Nullable TraceContext traceContext) {
			this.traceContext = traceContext;
			this.traceContextSpecified = true;
			return this;
		}

		@NonNull
		public PathBuilder remoteAddress(@Nullable InetSocketAddress remoteAddress) {
			this.remoteAddress = remoteAddress;
			return this;
		}

		@NonNull
		public PathBuilder body(@Nullable byte[] body) {
			this.body = body;
			return this;
		}

		@NonNull
		public PathBuilder contentTooLarge(@Nullable Boolean contentTooLarge) {
			this.contentTooLarge = contentTooLarge;
			return this;
		}

		@NonNull
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
		@NonNull
		private final PathBuilder builder;

		// Track original raw values and modification state
		@Nullable
		private String originalRawPath;
		@Nullable
		private String originalRawQuery;
		@Nullable
		private InetSocketAddress originalRemoteAddress;
		@Nullable
		private TraceContext originalTraceContext;
		@NonNull
		private Boolean pathModified = false;
		@NonNull
		private Boolean queryParametersModified = false;
		@NonNull
		private Boolean headersModified = false;
		@NonNull
		private Boolean traceContextModified = false;

		Copier(@NonNull Request request) {
			requireNonNull(request);

			this.originalRawPath = request.getRawPath();
			this.originalRawQuery = request.rawQuery; // Direct field access
			this.originalRemoteAddress = request.getRemoteAddress().orElse(null);
			this.originalTraceContext = request.getTraceContext().orElse(null);

			this.builder = new PathBuilder(request.getHttpMethod(), request.getPath())
					.id(request.getId())
					.queryParameters(mutableLinkedCopy(request.getQueryParameters()))
					.headers(mutableCaseInsensitiveCopy(request.getHeaders()))
					.body(request.body) // Direct field access to avoid array copy
					.multipartParser(request.getMultipartParser())
					.idGenerator(request.getIdGenerator())
					.contentTooLarge(request.isContentTooLarge())
					.remoteAddress(this.originalRemoteAddress)
					// Preserve original raw values initially
					.rawPath(this.originalRawPath)
					.rawQuery(this.originalRawQuery);
		}

		@NonNull
		public Copier httpMethod(@NonNull HttpMethod httpMethod) {
			requireNonNull(httpMethod);
			this.builder.httpMethod(httpMethod);
			return this;
		}

		@NonNull
		public Copier path(@NonNull String path) {
			requireNonNull(path);
			this.builder.path(path);
			this.pathModified = true;
			// Clear preserved raw path since decoded path changed
			this.builder.rawPath(null);
			return this;
		}

		@NonNull
		public Copier id(@Nullable Object id) {
			this.builder.id(id);
			return this;
		}

		@NonNull
		public Copier queryParameters(@Nullable Map<@NonNull String, @NonNull Set<@NonNull String>> queryParameters) {
			this.builder.queryParameters(queryParameters);
			this.queryParametersModified = true;
			// Clear preserved raw query since decoded query parameters changed
			this.builder.rawQuery(null);
			return this;
		}

		// Convenience method for mutation
		@NonNull
		public Copier queryParameters(@NonNull Consumer<Map<@NonNull String, @NonNull Set<@NonNull String>>> queryParametersConsumer) {
			requireNonNull(queryParametersConsumer);

			if (this.builder.queryParameters == null)
				this.builder.queryParameters(new LinkedHashMap<>());

			queryParametersConsumer.accept(this.builder.queryParameters);
			this.queryParametersModified = true;
			// Clear preserved raw query since decoded query parameters changed
			this.builder.rawQuery(null);
			return this;
		}

		@NonNull
		public Copier headers(@Nullable Map<@NonNull String, @NonNull Set<@NonNull String>> headers) {
			this.builder.headers(headers);
			this.headersModified = true;
			return this;
		}

		@NonNull
		public Copier traceContext(@Nullable TraceContext traceContext) {
			this.builder.traceContext(traceContext);
			this.traceContextModified = true;
			return this;
		}

		@NonNull
		public Copier remoteAddress(@Nullable InetSocketAddress remoteAddress) {
			this.builder.remoteAddress(remoteAddress);
			return this;
		}

		// Convenience method for mutation
		@NonNull
		public Copier headers(@NonNull Consumer<Map<@NonNull String, @NonNull Set<@NonNull String>>> headersConsumer) {
			requireNonNull(headersConsumer);

			if (this.builder.headers == null)
				this.builder.headers(new LinkedCaseInsensitiveMap<>());

			headersConsumer.accept(this.builder.headers);
			this.headersModified = true;
			return this;
		}

		@NonNull
		public Copier body(@Nullable byte[] body) {
			this.builder.body(body);
			return this;
		}

		@NonNull
		public Copier contentTooLarge(@Nullable Boolean contentTooLarge) {
			this.builder.contentTooLarge(contentTooLarge);
			return this;
		}

		@NonNull
		public Request finish() {
			if (this.queryParametersModified) {
				Map<String, Set<String>> queryParameters = this.builder.queryParameters;

				if (queryParameters == null || queryParameters.isEmpty()) {
					this.builder.rawQuery(null);
				} else {
					this.builder.rawQuery(Utilities.encodeQueryParameters(queryParameters, QueryFormat.RFC_3986_STRICT));
				}
			}

			if (!this.headersModified && !this.traceContextModified)
				this.builder.traceContext(this.originalTraceContext);

			return this.builder.build();
		}

		@NonNull
		private static Map<@NonNull String, @NonNull Set<@NonNull String>> mutableLinkedCopy(@NonNull Map<@NonNull String, @NonNull Set<@NonNull String>> valuesByName) {
			requireNonNull(valuesByName);

			Map<String, Set<String>> copy = new LinkedHashMap<>();
			for (Map.Entry<String, Set<String>> entry : valuesByName.entrySet())
				copy.put(entry.getKey(), entry.getValue() == null ? new LinkedHashSet<>() : new LinkedHashSet<>(entry.getValue()));

			return copy;
		}

		@NonNull
		private static Map<@NonNull String, @NonNull Set<@NonNull String>> mutableCaseInsensitiveCopy(@NonNull Map<@NonNull String, @NonNull Set<@NonNull String>> valuesByName) {
			requireNonNull(valuesByName);

			Map<String, Set<String>> copy = new LinkedCaseInsensitiveMap<>();
			for (Map.Entry<String, Set<String>> entry : valuesByName.entrySet())
				copy.put(entry.getKey(), entry.getValue() == null ? new LinkedHashSet<>() : new LinkedHashSet<>(entry.getValue()));

			return copy;
		}
	}
}
