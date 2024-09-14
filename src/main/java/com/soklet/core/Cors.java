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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.soklet.core.Utilities.trimAggressively;
import static com.soklet.core.Utilities.trimAggressivelyToEmpty;
import static com.soklet.core.Utilities.trimAggressivelyToNull;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Encapsulates all <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS">CORS</a>-related HTTP request data.
 * <p>
 * If this is a <a href="https://developer.mozilla.org/en-US/docs/Glossary/Preflight_request">preflight</a> request, the {@link #isPreflight()} method will return {@code true}.
 * <p>
 * See <a href="https://www.soklet.com/docs/cors">https://www.soklet.com/docs/cors</a> for detailed documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class Cors {
	@Nonnull
	private final String origin;
	@Nullable
	private final HttpMethod accessControlRequestMethod;
	@Nonnull
	private final Set<String> accessControlRequestHeaders;
	@Nonnull
	private final Boolean preflight;

	protected Cors(@Nonnull HttpMethod httpMethod,
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

	/**
	 * Constructs a CORS <strong>non-preflight</strong> request representation for the given HTTP request data.
	 *
	 * @param httpMethod the request's HTTP method
	 * @param origin     HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Origin">{@code Origin}</a> header value
	 * @return the CORS non-preflight request representation
	 */
	public static Cors forRequest(@Nonnull HttpMethod httpMethod,
																@Nonnull String origin) {
		requireNonNull(httpMethod);
		requireNonNull(origin);

		return new Cors(httpMethod, origin, null, null);
	}

	/**
	 * Constructs a CORS <strong>preflight</strong> request representation for the given HTTP request data.
	 * <p>
	 * CORS preflight requests always have method {@code OPTIONS} and specify their target method via
	 * the <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Request-Method">{@code Access-Control-Request-Method}</a> header value.
	 *
	 * @param origin                      HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Origin">{@code Origin}</a> header value
	 * @param accessControlRequestMethod  HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Request-Method">{@code Access-Control-Request-Method}</a> header value
	 * @param accessControlRequestHeaders the optional set of HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Request-Headers">{@code Access-Control-Request-Headers}</a> header values
	 * @return the CORS preflight request representation
	 */
	@Nonnull
	public static Cors forPreflightRequest(@Nonnull String origin,
																				 @Nonnull HttpMethod accessControlRequestMethod,
																				 @Nullable Set<String> accessControlRequestHeaders) {
		requireNonNull(origin);
		requireNonNull(accessControlRequestMethod);

		return new Cors(HttpMethod.OPTIONS, origin, accessControlRequestMethod, accessControlRequestHeaders);
	}

	/**
	 * Extracts a CORS request representation from the given HTTP request data.
	 *
	 * @param httpMethod the request's HTTP method
	 * @param headers    the request headers
	 * @return the CORS data for this request, or {@link Optional#empty()} if insufficent data is present
	 */
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

	/**
	 * Returns the value of the HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Origin">{@code Origin}</a> header value.
	 *
	 * @return the header value
	 */
	@Nonnull
	public String getOrigin() {
		return this.origin;
	}

	/**
	 * Is this a CORS <a href="https://developer.mozilla.org/en-US/docs/Glossary/Preflight_request">preflight</a> request?
	 *
	 * @return {@code true} if preflight, {@code false} otherwise
	 */
	@Nonnull
	public Boolean isPreflight() {
		return this.preflight;
	}

	/**
	 * Returns the value of the HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Request-Method">{@code Access-Control-Request-Method}</a> header value.
	 *
	 * @return the header value, or {@link Optional#empty()} if not present
	 */
	@Nonnull
	public Optional<HttpMethod> getAccessControlRequestMethod() {
		return Optional.ofNullable(this.accessControlRequestMethod);
	}

	/**
	 * Returns the set of values for the HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Request-Headers">{@code Access-Control-Request-Headers}</a> header.
	 *
	 * @return the set of header values, or the empty set if not present
	 */
	@Nonnull
	public Set<String> getAccessControlRequestHeaders() {
		return this.accessControlRequestHeaders;
	}
}