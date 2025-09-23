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

package com.soklet.core.impl;

import com.soklet.core.Cors;
import com.soklet.core.CorsPreflight;
import com.soklet.core.CorsPreflightResponse;
import com.soklet.core.CorsResponse;
import com.soklet.core.HttpMethod;
import com.soklet.core.MarshaledResponse;
import com.soklet.core.Request;
import com.soklet.core.ResourceMethod;
import com.soklet.core.Response;
import com.soklet.core.ResponseMarshaler;
import com.soklet.core.StatusCode;
import com.soklet.exception.BadRequestException;
import com.soklet.internal.spring.LinkedCaseInsensitiveMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.soklet.core.Utilities.emptyByteArray;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class DefaultResponseMarshaler implements ResponseMarshaler {
	@Nonnull
	private static final DefaultResponseMarshaler SHARED_INSTANCE;
	@Nonnull
	private static final Charset DEFAULT_CHARSET;

	static {
		DEFAULT_CHARSET = StandardCharsets.UTF_8;
		SHARED_INSTANCE = new DefaultResponseMarshaler();
	}

	@Nonnull
	private final Charset charset;

	public DefaultResponseMarshaler() {
		this(null);
	}

	public DefaultResponseMarshaler(@Nullable Charset charset) {
		this.charset = charset == null ? DEFAULT_CHARSET : charset;
	}

	@Nonnull
	public static DefaultResponseMarshaler sharedInstance() {
		return SHARED_INSTANCE;
	}

	@Nonnull
	@Override
	public MarshaledResponse forHappyPath(@Nonnull Request request,
																				@Nonnull Response response,
																				@Nonnull ResourceMethod resourceMethod) {
		requireNonNull(request);
		requireNonNull(response);
		requireNonNull(resourceMethod);

		byte[] body = null;
		Object bodyAsObject = response.getBody().orElse(null);
		boolean binaryResponse = false;

		// If response body is a byte array, pass through as-is.
		// Otherwise, default representation is toString() output.
		// Real systems would use a different representation, e.g. JSON
		if (bodyAsObject != null) {
			if (bodyAsObject instanceof byte[]) {
				body = (byte[]) bodyAsObject;
				binaryResponse = true;
			} else {
				body = bodyAsObject.toString().getBytes(getCharset());
			}
		}

		Map<String, Set<String>> headers = new LinkedCaseInsensitiveMap<>(response.getHeaders());

		// If no Content-Type specified, supply a default
		if (!headers.keySet().contains("Content-Type"))
			headers.put("Content-Type", Set.of(binaryResponse ? "application/octet-stream" : format("text/plain; charset=%s", getCharset().name())));

		return MarshaledResponse.withStatusCode(response.getStatusCode())
				.headers(headers)
				.cookies(response.getCookies())
				.body(body)
				.build();
	}

	@Nonnull
	@Override
	public MarshaledResponse forNotFound(@Nonnull Request request) {
		requireNonNull(request);

		Integer statusCode = 404;

		return MarshaledResponse.withStatusCode(statusCode)
				.headers(Map.of("Content-Type", Set.of(format("text/plain; charset=%s", getCharset().name()))))
				.body(format("HTTP %d: %s", statusCode, StatusCode.fromStatusCode(statusCode).get().getReasonPhrase()).getBytes(getCharset()))
				.build();
	}

	@Nonnull
	@Override
	public MarshaledResponse forMethodNotAllowed(@Nonnull Request request,
																							 @Nonnull Set<HttpMethod> allowedHttpMethods) {
		requireNonNull(request);
		requireNonNull(allowedHttpMethods);

		SortedSet<String> allowedHttpMethodsAsStrings = new TreeSet<>(allowedHttpMethods.stream()
				.map(httpMethod -> httpMethod.name())
				.collect(Collectors.toSet()));

		Integer statusCode = 405;

		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("Allow", allowedHttpMethodsAsStrings);
		headers.put("Content-Type", Set.of(format("text/plain; charset=%s", getCharset().name())));

		return MarshaledResponse.withStatusCode(statusCode)
				.headers(headers)
				.body(format("HTTP %d: %s. Requested: %s, Allowed: %s",
						statusCode, StatusCode.fromStatusCode(statusCode).get().getReasonPhrase(), request.getHttpMethod().name(),
						String.join(", ", allowedHttpMethodsAsStrings)).getBytes(getCharset()))
				.build();
	}

	@Nonnull
	@Override
	public MarshaledResponse forContentTooLarge(@Nonnull Request request,
																							@Nullable ResourceMethod resourceMethod) {
		requireNonNull(request);

		Integer statusCode = 413;

		return MarshaledResponse.withStatusCode(statusCode)
				.headers(Map.of("Content-Type", Set.of(format("text/plain; charset=%s", getCharset().name()))))
				.body(format("HTTP %d: %s", statusCode, StatusCode.fromStatusCode(statusCode).get().getReasonPhrase()).getBytes(getCharset()))
				.build();
	}

	@Nonnull
	@Override
	public MarshaledResponse forOptions(@Nonnull Request request,
																			@Nonnull Set<HttpMethod> allowedHttpMethods) {
		requireNonNull(request);
		requireNonNull(allowedHttpMethods);

		SortedSet<String> allowedHttpMethodsAsStrings = new TreeSet<>(allowedHttpMethods.stream()
				.map(httpMethod -> httpMethod.name())
				.collect(Collectors.toSet()));

		return MarshaledResponse.withStatusCode(204)
				.headers(Map.of("Allow", allowedHttpMethodsAsStrings))
				.build();
	}

	@Nonnull
	@Override
	public MarshaledResponse forHead(@Nonnull Request request,
																	 @Nonnull MarshaledResponse getMethodMarshaledResponse) {
		requireNonNull(request);
		requireNonNull(getMethodMarshaledResponse);

		// A HEAD can never write a response body, but we explicitly set its Content-Length header
		// so the client knows how long the response would have been.
		return getMethodMarshaledResponse.copy()
				.body(null)
				.headers((mutableHeaders) -> {
					byte[] responseBytes = getMethodMarshaledResponse.getBody().orElse(emptyByteArray());
					mutableHeaders.put("Content-Length", Set.of(String.valueOf(responseBytes.length)));
				}).finish();
	}

	@Nonnull
	@Override
	public MarshaledResponse forThrowable(@Nonnull Request request,
																				@Nonnull Throwable throwable,
																				@Nullable ResourceMethod resourceMethod) {
		requireNonNull(request);
		requireNonNull(throwable);

		Integer statusCode = throwable instanceof BadRequestException ? 400 : 500;

		return MarshaledResponse.withStatusCode(statusCode)
				.headers(Map.of("Content-Type", Set.of(format("text/plain; charset=%s", getCharset().name()))))
				.body(format("HTTP %d: %s", statusCode, StatusCode.fromStatusCode(statusCode).get().getReasonPhrase()).getBytes(getCharset()))
				.build();
	}

	@Nonnull
	@Override
	public MarshaledResponse forCorsPreflightAllowed(@Nonnull Request request,
																									 @Nonnull CorsPreflight corsPreflight,
																									 @Nonnull CorsPreflightResponse corsPreflightResponse) {
		requireNonNull(request);
		requireNonNull(corsPreflight);
		requireNonNull(corsPreflightResponse);

		Integer statusCode = 204;
		Map<String, Set<String>> headers = new LinkedHashMap<>();

		Boolean accessControlAllowCredentials = corsPreflightResponse.getAccessControlAllowCredentials().orElse(null);

		String normalizedAccessControlAllowOrigin = normalizedAccessControlAllowOrigin(
				corsPreflight.getOrigin(),
				corsPreflightResponse.getAccessControlAllowOrigin(),
				accessControlAllowCredentials
		);

		headers.put("Access-Control-Allow-Origin", Set.of(normalizedAccessControlAllowOrigin));

		// Either "true" or omit entirely
		if (accessControlAllowCredentials != null && accessControlAllowCredentials)
			headers.put("Access-Control-Allow-Credentials", Set.of("true"));

		// If we turned "*" into the full origin, add Vary: Origin
		if (!normalizedAccessControlAllowOrigin.equals(corsPreflightResponse.getAccessControlAllowOrigin()))
			headers.put("Vary", Set.of("Origin"));

		Set<String> accessControlAllowHeaders = corsPreflightResponse.getAccessControlAllowHeaders();

		if (accessControlAllowHeaders.size() > 0)
			headers.put("Access-Control-Allow-Headers", new LinkedHashSet<>(accessControlAllowHeaders));

		Set<String> accessControlAllowMethodAsStrings = new LinkedHashSet<>();

		for (HttpMethod httpMethod : corsPreflightResponse.getAccessControlAllowMethods())
			accessControlAllowMethodAsStrings.add(httpMethod.name());

		if (accessControlAllowMethodAsStrings.size() > 0)
			headers.put("Access-Control-Allow-Methods", accessControlAllowMethodAsStrings);

		Duration accessControlMaxAge = corsPreflightResponse.getAccessControlMaxAge().orElse(null);

		if (accessControlMaxAge != null)
			headers.put("Access-Control-Max-Age", Set.of(String.valueOf(accessControlMaxAge.toSeconds())));

		return MarshaledResponse.withStatusCode(statusCode)
				.headers(headers)
				.build();
	}

	@Nonnull
	@Override
	public MarshaledResponse forCorsPreflightRejected(@Nonnull Request request,
																										@Nonnull CorsPreflight corsPreflight) {
		requireNonNull(request);
		requireNonNull(corsPreflight);

		Integer statusCode = 403;

		return MarshaledResponse.withStatusCode(statusCode)
				.headers(Map.of("Content-Type", Set.of(format("text/plain; charset=%s", getCharset().name()))))
				.body(format("HTTP %d: %s (CORS preflight rejected)", statusCode,
						StatusCode.fromStatusCode(statusCode).get().getReasonPhrase()).getBytes(getCharset()))
				.build();
	}

	@Nonnull
	@Override
	public MarshaledResponse forCorsAllowed(@Nonnull Request request,
																					@Nonnull Cors cors,
																					@Nonnull CorsResponse corsResponse,
																					@Nonnull MarshaledResponse marshaledResponse) {
		requireNonNull(request);
		requireNonNull(cors);
		requireNonNull(corsResponse);
		requireNonNull(marshaledResponse);

		return marshaledResponse.copy()
				.headers((mutableHeaders) -> {
					Boolean accessControlAllowCredentials = corsResponse.getAccessControlAllowCredentials().orElse(null);

					String normalizedAccessControlAllowOrigin = normalizedAccessControlAllowOrigin(
							cors.getOrigin(),
							corsResponse.getAccessControlAllowOrigin(),
							accessControlAllowCredentials
					);

					mutableHeaders.put("Access-Control-Allow-Origin", Set.of(normalizedAccessControlAllowOrigin));

					// Either "true" or omit entirely
					if (accessControlAllowCredentials != null && accessControlAllowCredentials)
						mutableHeaders.put("Access-Control-Allow-Credentials", Set.of("true"));

					// If we turned "*" into the full origin, add Vary: Origin...
					if (!normalizedAccessControlAllowOrigin.equals(corsResponse.getAccessControlAllowOrigin())) {
						// ...and preserve any existing Vary values
						Set<String> vary = new LinkedHashSet<>(mutableHeaders.getOrDefault("Vary", Set.of()));
						vary.add("Origin");
						mutableHeaders.put("Vary", vary);
					}

					Set<String> accessControlExposeHeaders = corsResponse.getAccessControlExposeHeaders();

					if (accessControlExposeHeaders.size() > 0)
						mutableHeaders.put("Access-Control-Expose-Headers", new LinkedHashSet<>(accessControlExposeHeaders));
				}).finish();
	}

	@Nonnull
	private String normalizedAccessControlAllowOrigin(@Nonnull String origin,
																										@Nonnull String accessControlAllowOrigin,
																										@Nullable Boolean accessControlAllowCredentials) {
		requireNonNull(origin);
		requireNonNull(accessControlAllowOrigin);

		// If credentials are allowed, "*" is forbidden and must echo the request Origin
		if (Objects.equals(Boolean.TRUE, accessControlAllowCredentials) && "*".equals(accessControlAllowOrigin.trim()))
			return origin;

		return accessControlAllowOrigin;
	}

	@Nonnull
	protected Charset getCharset() {
		return this.charset;
	}
}
