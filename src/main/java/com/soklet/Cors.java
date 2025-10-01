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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.soklet.Utilities.trimAggressivelyToNull;
import static java.util.Objects.requireNonNull;

/**
 * Encapsulates <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS">non-preflight CORS</a> HTTP request data.
 * <p>
 * Data for <a href="https://developer.mozilla.org/en-US/docs/Glossary/Preflight_request">preflight</a> requests is represented by {@link CorsPreflight}.
 * <p>
 * See <a href="https://www.soklet.com/docs/cors">https://www.soklet.com/docs/cors</a> for detailed documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class Cors {
	@Nullable
	private final HttpMethod httpMethod;
	@Nonnull
	private final String origin;

	/**
	 * Acquires a CORS <strong>non-preflight</strong> request representation for the given HTTP request data.
	 *
	 * @param httpMethod the request's HTTP method
	 * @param origin     HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Origin">{@code Origin}</a> request header value
	 * @return a {@link Cors} instance
	 */
	@Nonnull
	public static Cors with(@Nonnull HttpMethod httpMethod,
													@Nonnull String origin) {
		requireNonNull(httpMethod);
		requireNonNull(origin);

		return new Cors(httpMethod, origin);
	}

	/**
	 * Extracts a CORS <strong>non-preflight</strong> request representation from the given HTTP request data.
	 *
	 * @param httpMethod the request's HTTP method
	 * @param headers    the request headers
	 * @return the CORS non-preflight data for this request, or {@link Optional#empty()} if insufficent data is present
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

		return Optional.of(new Cors(httpMethod, originHeaderValue));
	}

	private Cors(@Nonnull HttpMethod httpMethod,
							 @Nonnull String origin) {
		requireNonNull(httpMethod);
		requireNonNull(origin);

		this.httpMethod = httpMethod;
		this.origin = origin;
	}

	@Override
	@Nonnull
	public String toString() {
		return String.format("%s{httpMethod=%s, origin=%s}", getClass().getSimpleName(), getHttpMethod().name(), getOrigin());
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof Cors cors))
			return false;

		return Objects.equals(getHttpMethod(), cors.getHttpMethod())
				&& Objects.equals(getOrigin(), cors.getOrigin());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getHttpMethod(), getOrigin());
	}

	/**
	 * The HTTP method for this request.
	 *
	 * @return the HTTP method
	 */
	@Nullable
	public HttpMethod getHttpMethod() {
		return this.httpMethod;
	}

	/**
	 * The HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Origin">{@code Origin}</a> header value for this request.
	 *
	 * @return the header value
	 */
	@Nonnull
	public String getOrigin() {
		return this.origin;
	}
}