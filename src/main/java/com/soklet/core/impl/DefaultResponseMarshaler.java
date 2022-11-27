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

package com.soklet.core.impl;

import com.soklet.core.CorsRequest;
import com.soklet.core.CorsResponse;
import com.soklet.core.HttpMethod;
import com.soklet.core.MarshaledResponse;
import com.soklet.core.Request;
import com.soklet.core.ResourceMethod;
import com.soklet.core.Response;
import com.soklet.core.ResponseMarshaler;
import com.soklet.core.StatusCode;
import com.soklet.exception.BadRequestException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
@ThreadSafe
public class DefaultResponseMarshaler implements ResponseMarshaler {
	@Nonnull
	@Override
	public MarshaledResponse forHappyPath(@Nonnull Request request,
																				@Nonnull Response response,
																				@Nonnull ResourceMethod resourceMethod) {
		requireNonNull(request);
		requireNonNull(response);
		requireNonNull(resourceMethod);

		Map<String, Set<String>> headers = response.getHeaders();

		Set<String> normalizedHeaderKeys = headers.keySet().stream()
				.map(key -> key.trim().toLowerCase(Locale.US))
				.collect(Collectors.toSet());

		// If no Content-Type specified, supply a default
		if (!normalizedHeaderKeys.contains("content-type")) {
			headers = new HashMap<>(headers); // Mutable copy
			headers.put("Content-Type", Set.of("text/plain; charset=UTF-8"));
		}

		return new MarshaledResponse.Builder(response.getStatusCode())
				.headers(headers)
				.cookies(response.getCookies())
				.body(response.getBody().isPresent() ? response.getBody().get().toString().getBytes(StandardCharsets.UTF_8) : null)
				.build();
	}

	@Nonnull
	@Override
	public MarshaledResponse forNotFound(@Nonnull Request request) {
		requireNonNull(request);

		Integer statusCode = 404;

		return new MarshaledResponse.Builder(statusCode)
				.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
				.body(format("HTTP %d: %s", statusCode, StatusCode.fromStatusCode(statusCode).get().getReasonPhrase()).getBytes(StandardCharsets.UTF_8))
				.build();
	}

	@Nonnull
	@Override
	public MarshaledResponse forMethodNotAllowed(@Nonnull Request request,
																							 @Nonnull Set<HttpMethod> allowedHttpMethods) {
		requireNonNull(request);
		requireNonNull(allowedHttpMethods);

		Set<String> allowedHttpMethodsAsStrings = allowedHttpMethods.stream()
				.map(httpMethod -> httpMethod.name())
				.collect(Collectors.toSet());

		Integer statusCode = 405;

		return new MarshaledResponse.Builder(statusCode)
				.headers(Map.of("Allow", allowedHttpMethodsAsStrings, "Content-Type", Set.of("text/plain; charset=UTF-8")))
				.body(format("HTTP %d: %s. Requested: %s, Allowed: %s",
						statusCode, StatusCode.fromStatusCode(statusCode).get().getReasonPhrase(), request.getHttpMethod().name(),
						allowedHttpMethodsAsStrings.stream().collect(Collectors.joining(", "))).getBytes(StandardCharsets.UTF_8))
				.build();
	}

	@Nonnull
	@Override
	public MarshaledResponse forOptions(@Nonnull Request request,
																			@Nonnull Set<HttpMethod> allowedHttpMethods) {
		requireNonNull(request);
		requireNonNull(allowedHttpMethods);

		Set<String> allowedHttpMethodsAsStrings = allowedHttpMethods.stream()
				.map(httpMethod -> httpMethod.name())
				.collect(Collectors.toSet());

		return new MarshaledResponse.Builder(204)
				.headers(Map.of("Allow", allowedHttpMethodsAsStrings))
				.build();
	}

	@Nonnull
	@Override
	public MarshaledResponse forCorsAllowed(@Nonnull Request request,
																					@Nonnull CorsRequest corsRequest,
																					@Nonnull CorsResponse corsResponse) {
		requireNonNull(request);
		requireNonNull(corsRequest);
		requireNonNull(corsResponse);

		Integer statusCode = 204;
		Map<String, Set<String>> headers = new HashMap<>();

		headers.put("Access-Control-Allow-Origin", Set.of(corsResponse.getAccessControlAllowOrigin()));

		Boolean accessControlAllowCredentials = corsResponse.getAccessControlAllowCredentials().orElse(null);

		if (accessControlAllowCredentials != null)
			headers.put("Access-Control-Allow-Credentials", Set.of(String.valueOf(accessControlAllowCredentials)));

		Set<String> accessControlAllowHeaders = corsResponse.getAccessControlAllowHeaders();

		if (accessControlAllowHeaders.size() > 0)
			headers.put("Access-Control-Allow-Headers", new HashSet<>(accessControlAllowHeaders));

		Set<String> accessControlExposeHeaders = corsResponse.getAccessControlExposeHeaders();

		if (accessControlExposeHeaders.size() > 0)
			headers.put("Access-Control-Expose-Headers", new HashSet<>(accessControlExposeHeaders));

		Set<String> accessControlAllowMethodAsStrings = corsResponse.getAccessControlAllowMethods().stream()
				.map(accessControlAllowMethod -> accessControlAllowMethod.name())
				.collect(Collectors.toSet());

		if (accessControlAllowMethodAsStrings.size() > 0)
			headers.put("Access-Control-Allow-Methods", accessControlAllowMethodAsStrings);

		Integer accessControlMaxAge = corsResponse.getAccessControlMaxAge().orElse(null);

		if (accessControlMaxAge != null)
			headers.put("Access-Control-Max-Age", Set.of(String.valueOf(accessControlMaxAge)));

		return new MarshaledResponse.Builder(statusCode)
				.headers(headers)
				.build();
	}

	@Nonnull
	@Override
	public MarshaledResponse forCorsRejected(@Nonnull Request request,
																					 @Nonnull CorsRequest corsRequest) {
		requireNonNull(request);
		requireNonNull(corsRequest);

		Integer statusCode = 403;

		return new MarshaledResponse.Builder(statusCode)
				.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
				.body(format("HTTP %d: %s (CORS Rejected)", statusCode,
						StatusCode.fromStatusCode(statusCode).get().getReasonPhrase()).getBytes(StandardCharsets.UTF_8))
				.build();
	}

	@Nonnull
	@Override
	public MarshaledResponse forException(@Nonnull Request request,
																				@Nonnull Throwable throwable,
																				@Nullable ResourceMethod resourceMethod) {
		requireNonNull(request);
		requireNonNull(throwable);

		Integer statusCode = throwable instanceof BadRequestException ? 400 : 500;

		return new MarshaledResponse.Builder(statusCode)
				.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
				.body(format("HTTP %d: %s", statusCode, StatusCode.fromStatusCode(statusCode).get().getReasonPhrase()).getBytes(StandardCharsets.UTF_8))
				.build();
	}
}
