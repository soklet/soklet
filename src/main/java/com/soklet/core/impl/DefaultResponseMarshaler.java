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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.soklet.core.Utilities.emptyByteArray;
import static com.soklet.core.Utilities.trimAggressively;
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
				.map(key -> trimAggressively(key).toLowerCase(Locale.US))
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
						String.join(", ", allowedHttpMethodsAsStrings)).getBytes(StandardCharsets.UTF_8))
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
	public MarshaledResponse forHead(@Nonnull Request request,
																	 @Nonnull MarshaledResponse getMarshaledResponse) {
		requireNonNull(request);
		requireNonNull(getMarshaledResponse);

		// A HEAD can never write a response body, but we explicitly set its Content-Length header
		// so the client knows how long the response would have been.
		return getMarshaledResponse.copy()
				.body(oldBody -> null)
				.headers((mutableHeaders) -> {
					byte[] responseBytes = getMarshaledResponse.getBody().orElse(emptyByteArray());
					mutableHeaders.put("Content-Length", Set.of(String.valueOf(responseBytes.length)));
				}).finish();
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

	@Nonnull
	@Override
	public MarshaledResponse forCorsPreflightAllowed(@Nonnull Request request,
																									 @Nonnull CorsPreflightResponse corsPreflightResponse) {
		requireNonNull(request);
		requireNonNull(corsPreflightResponse);

		Integer statusCode = 204;
		Map<String, Set<String>> headers = new HashMap<>();

		headers.put("Access-Control-Allow-Origin", Set.of(corsPreflightResponse.getAccessControlAllowOrigin()));

		Boolean accessControlAllowCredentials = corsPreflightResponse.getAccessControlAllowCredentials().orElse(null);

		// Either "true" or omit entirely
		if (accessControlAllowCredentials != null && accessControlAllowCredentials)
			headers.put("Access-Control-Allow-Credentials", Set.of("true"));

		Set<String> accessControlAllowHeaders = corsPreflightResponse.getAccessControlAllowHeaders();

		if (accessControlAllowHeaders.size() > 0)
			headers.put("Access-Control-Allow-Headers", new HashSet<>(accessControlAllowHeaders));

		Set<String> accessControlAllowMethodAsStrings = corsPreflightResponse.getAccessControlAllowMethods().stream()
				.map(accessControlAllowMethod -> accessControlAllowMethod.name())
				.collect(Collectors.toSet());

		if (accessControlAllowMethodAsStrings.size() > 0)
			headers.put("Access-Control-Allow-Methods", accessControlAllowMethodAsStrings);

		Duration accessControlMaxAge = corsPreflightResponse.getAccessControlMaxAge().orElse(null);

		if (accessControlMaxAge != null)
			headers.put("Access-Control-Max-Age", Set.of(String.valueOf(accessControlMaxAge.toSeconds())));

		return new MarshaledResponse.Builder(statusCode)
				.headers(headers)
				.build();
	}

	@Nonnull
	@Override
	public MarshaledResponse forCorsPreflightRejected(@Nonnull Request request) {
		requireNonNull(request);

		Integer statusCode = 403;

		return new MarshaledResponse.Builder(statusCode)
				.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
				.body(format("HTTP %d: %s (CORS preflight rejected)", statusCode,
						StatusCode.fromStatusCode(statusCode).get().getReasonPhrase()).getBytes(StandardCharsets.UTF_8))
				.build();
	}

	@Nonnull
	@Override
	public MarshaledResponse forCorsAllowed(@Nonnull Request request,
																					@Nonnull CorsResponse corsResponse,
																					@Nonnull MarshaledResponse marshaledResponse) {
		requireNonNull(request);
		requireNonNull(corsResponse);
		requireNonNull(marshaledResponse);

		return marshaledResponse.copy()
				.headers((mutableHeaders) -> {
					mutableHeaders.put("Access-Control-Allow-Origin", Set.of(corsResponse.getAccessControlAllowOrigin()));

					Boolean accessControlAllowCredentials = corsResponse.getAccessControlAllowCredentials().orElse(null);

					// Either "true" or omit entirely
					if (accessControlAllowCredentials != null && accessControlAllowCredentials)
						mutableHeaders.put("Access-Control-Allow-Credentials", Set.of("true"));

					Set<String> accessControlExposeHeaders = corsResponse.getAccessControlExposeHeaders();

					if (accessControlExposeHeaders.size() > 0)
						mutableHeaders.put("Access-Control-Expose-Headers", new HashSet<>(accessControlExposeHeaders));
				}).finish();
	}
}
