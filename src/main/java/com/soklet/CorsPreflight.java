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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.soklet.Utilities.trimAggressively;
import static com.soklet.Utilities.trimAggressivelyToEmpty;
import static com.soklet.Utilities.trimAggressivelyToNull;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Encapsulates <a href="https://developer.mozilla.org/en-US/docs/Glossary/Preflight_request">CORS preflight</a>-related HTTP request data.
 * <p>
 * Instances can be acquired via these factory methods:
 * <ul>
 *   <li>{@link #with(String, HttpMethod)} (uses {@code Origin} and {@code Access-Control-Request-Method} header values)</li>
 *   <li>{@link #with(String, HttpMethod, Set)} (uses {@code Origin}, {@code Access-Control-Request-Method}, and {@code Access-Control-Request-Headers} header values)</li>
 *   <li>{@link #fromHeaders(Map)} (parses raw headers)</li>
 * </ul>
 * Data for <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS">non-preflight CORS</a> requests is represented by {@link Cors}.
 * <p>
 * See <a href="https://www.soklet.com/docs/cors">https://www.soklet.com/docs/cors</a> for detailed documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class CorsPreflight {
	@Nonnull
	private final String origin;
	@Nullable
	private final HttpMethod accessControlRequestMethod;
	@Nonnull
	private final Set<String> accessControlRequestHeaders;

	/**
	 * Acquires a CORS <strong>preflight</strong> request representation for the given HTTP request data.
	 * <p>
	 * CORS preflight requests always have method {@code OPTIONS} and specify their target method via
	 * the <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Request-Method">{@code Access-Control-Request-Method}</a> header value.
	 *
	 * @param origin                     HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Origin">{@code Origin}</a> request header value
	 * @param accessControlRequestMethod HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Request-Method">{@code Access-Control-Request-Method}</a> request header value
	 * @return a {@link CorsPreflight} instance
	 */
	@Nonnull
	public static CorsPreflight with(@Nonnull String origin,
																	 @Nonnull HttpMethod accessControlRequestMethod) {
		requireNonNull(origin);
		requireNonNull(accessControlRequestMethod);

		return new CorsPreflight(origin, accessControlRequestMethod, null);
	}

	/**
	 * Acquires a CORS <strong>preflight</strong> request representation for the given HTTP request data.
	 * <p>
	 * CORS preflight requests always have method {@code OPTIONS} and specify their target method via
	 * the <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Request-Method">{@code Access-Control-Request-Method}</a> request value.
	 *
	 * @param origin                      HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Origin">{@code Origin}</a> request header value
	 * @param accessControlRequestMethod  HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Request-Method">{@code Access-Control-Request-Method}</a> request header value
	 * @param accessControlRequestHeaders the optional set of HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Request-Headers">{@code Access-Control-Request-Headers}</a> request header values
	 * @return a {@link CorsPreflight} instance
	 */
	@Nonnull
	public static CorsPreflight with(@Nonnull String origin,
																	 @Nonnull HttpMethod accessControlRequestMethod,
																	 @Nullable Set<String> accessControlRequestHeaders) {
		requireNonNull(origin);
		requireNonNull(accessControlRequestMethod);

		return new CorsPreflight(origin, accessControlRequestMethod, accessControlRequestHeaders);
	}

	/**
	 * Extracts a <a href="https://developer.mozilla.org/en-US/docs/Glossary/Preflight_request">CORS preflight request</a> representation from the given HTTP request data.
	 * <p>
	 * Note that only HTTP {@code OPTIONS} requests qualify to be CORS preflight requests.
	 *
	 * @param headers the request headers
	 * @return the CORS preflight data for this request, or {@link Optional#empty()} if insufficient data is present
	 */
	@Nonnull
	public static Optional<CorsPreflight> fromHeaders(@Nonnull Map<String, Set<String>> headers) {
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

		return Optional.of(new CorsPreflight(originHeaderValue,
				accessControlRequestMethods.size() > 0 ? accessControlRequestMethods.get(0) : null,
				accessControlRequestHeaderValues));
	}

	private CorsPreflight(@Nonnull String origin,
												@Nonnull HttpMethod accessControlRequestMethod,
												@Nullable Set<String> accessControlRequestHeaders) {
		requireNonNull(origin);
		requireNonNull(accessControlRequestMethod);

		this.origin = origin;
		this.accessControlRequestMethod = accessControlRequestMethod;
		this.accessControlRequestHeaders = accessControlRequestHeaders == null ?
				Set.of() : Set.copyOf(accessControlRequestHeaders);
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

		if (!(object instanceof CorsPreflight cors))
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
	 * Returns the HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Origin">{@code Origin}</a> request header value.
	 *
	 * @return the header value
	 */
	@Nonnull
	public String getOrigin() {
		return this.origin;
	}

	/**
	 * The HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Request-Method">{@code Access-Control-Request-Method}</a> request header value.
	 *
	 * @return the header value
	 */
	@Nonnull
	public HttpMethod getAccessControlRequestMethod() {
		return this.accessControlRequestMethod;
	}

	/**
	 * Returns the set of values for the HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Request-Headers">{@code Access-Control-Request-Headers}</a> request header.
	 *
	 * @return the set of header values, or the empty set if not present
	 */
	@Nonnull
	public Set<String> getAccessControlRequestHeaders() {
		return this.accessControlRequestHeaders;
	}
}