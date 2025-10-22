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

import com.soklet.ResponseMarshaler.Builder.ContentTooLargeHandler;
import com.soklet.ResponseMarshaler.Builder.CorsAllowedHandler;
import com.soklet.ResponseMarshaler.Builder.CorsPreflightAllowedHandler;
import com.soklet.ResponseMarshaler.Builder.CorsPreflightRejectedHandler;
import com.soklet.ResponseMarshaler.Builder.HeadHandler;
import com.soklet.ResponseMarshaler.Builder.MethodNotAllowedHandler;
import com.soklet.ResponseMarshaler.Builder.NotFoundHandler;
import com.soklet.ResponseMarshaler.Builder.OptionsHandler;
import com.soklet.ResponseMarshaler.Builder.PostProcessor;
import com.soklet.ResponseMarshaler.Builder.ResourceMethodHandler;
import com.soklet.ResponseMarshaler.Builder.ThrowableHandler;
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
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.soklet.Utilities.emptyByteArray;
import static com.soklet.Utilities.trimAggressivelyToEmpty;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultResponseMarshaler implements ResponseMarshaler {
	@Nonnull
	private static final DefaultResponseMarshaler DEFAULT_INSTANCE;

	static {
		DEFAULT_INSTANCE = new DefaultResponseMarshaler(ResponseMarshaler.withCharset(StandardCharsets.UTF_8));
	}

	@Nonnull
	public static DefaultResponseMarshaler defaultInstance() {
		return DEFAULT_INSTANCE;
	}

	@Nonnull
	private final Charset charset;
	@Nullable
	private final ResourceMethodHandler resourceMethodHandler;
	@Nullable
	private final NotFoundHandler notFoundHandler;
	@Nullable
	private final MethodNotAllowedHandler methodNotAllowedHandler;
	@Nullable
	private final ContentTooLargeHandler contentTooLargeHandler;
	@Nullable
	private final OptionsHandler optionsHandler;
	@Nullable
	private final ThrowableHandler throwableHandler;
	@Nullable
	private final HeadHandler headHandler;
	@Nullable
	private final CorsPreflightAllowedHandler corsPreflightAllowedHandler;
	@Nullable
	private final CorsPreflightRejectedHandler corsPreflightRejectedHandler;
	@Nullable
	private final CorsAllowedHandler corsAllowedHandler;
	@Nullable
	private final PostProcessor postProcessor;

	public DefaultResponseMarshaler(@Nonnull ResponseMarshaler.Builder builder) {
		requireNonNull(builder);

		this.charset = builder.charset;
		this.resourceMethodHandler = builder.resourceMethodHandler;
		this.notFoundHandler = builder.notFoundHandler;
		this.methodNotAllowedHandler = builder.methodNotAllowedHandler;
		this.contentTooLargeHandler = builder.contentTooLargeHandler;
		this.optionsHandler = builder.optionsHandler;
		this.throwableHandler = builder.throwableHandler;
		this.headHandler = builder.headHandler;
		this.corsPreflightAllowedHandler = builder.corsPreflightAllowedHandler;
		this.corsPreflightRejectedHandler = builder.corsPreflightRejectedHandler;
		this.corsAllowedHandler = builder.corsAllowedHandler;
		this.postProcessor = builder.postProcessor;
	}

	@Nonnull
	@Override
	public MarshaledResponse forResourceMethod(@Nonnull Request request,
																						 @Nonnull Response response,
																						 @Nonnull ResourceMethod resourceMethod) {
		requireNonNull(request);
		requireNonNull(response);
		requireNonNull(resourceMethod);

		ResourceMethodHandler resourceMethodHandler = getResourceMethodHandler().orElse(null);
		PostProcessor postProcessor = getPostProcessor().orElse(null);
		MarshaledResponse marshaledResponse;

		if (resourceMethodHandler != null) {
			marshaledResponse = resourceMethodHandler.handle(request, response, resourceMethod);
		} else {
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

			marshaledResponse = MarshaledResponse.withStatusCode(response.getStatusCode())
					.headers(headers)
					.cookies(response.getCookies())
					.body(body)
					.build();
		}

		if (postProcessor != null)
			marshaledResponse = postProcessor.postProcess(marshaledResponse);

		return marshaledResponse;
	}

	@Nonnull
	@Override
	public MarshaledResponse forNotFound(@Nonnull Request request) {
		requireNonNull(request);

		NotFoundHandler notFoundHandler = getNotFoundHandler().orElse(null);
		PostProcessor postProcessor = getPostProcessor().orElse(null);
		MarshaledResponse marshaledResponse;

		if (notFoundHandler != null) {
			marshaledResponse = notFoundHandler.handle(request);
		} else {
			Integer statusCode = 404;

			marshaledResponse = MarshaledResponse.withStatusCode(statusCode)
					.headers(Map.of("Content-Type", Set.of(format("text/plain; charset=%s", getCharset().name()))))
					.body(format("HTTP %d: %s", statusCode, StatusCode.fromStatusCode(statusCode).get().getReasonPhrase()).getBytes(getCharset()))
					.build();
		}

		if (postProcessor != null)
			marshaledResponse = postProcessor.postProcess(marshaledResponse);

		return marshaledResponse;
	}

	@Nonnull
	@Override
	public MarshaledResponse forMethodNotAllowed(@Nonnull Request request,
																							 @Nonnull Set<HttpMethod> allowedHttpMethods) {
		requireNonNull(request);
		requireNonNull(allowedHttpMethods);

		MethodNotAllowedHandler methodNotAllowedHandler = getMethodNotAllowedHandler().orElse(null);
		PostProcessor postProcessor = getPostProcessor().orElse(null);
		MarshaledResponse marshaledResponse;

		if (methodNotAllowedHandler != null) {
			marshaledResponse = methodNotAllowedHandler.handle(request, allowedHttpMethods);
		} else {
			SortedSet<String> allowedHttpMethodsAsStrings = new TreeSet<>(allowedHttpMethods.stream()
					.map(httpMethod -> httpMethod.name())
					.collect(Collectors.toSet()));

			Integer statusCode = 405;

			Map<String, Set<String>> headers = new LinkedHashMap<>();
			headers.put("Allow", allowedHttpMethodsAsStrings);
			headers.put("Content-Type", Set.of(format("text/plain; charset=%s", getCharset().name())));

			marshaledResponse = MarshaledResponse.withStatusCode(statusCode)
					.headers(headers)
					.body(format("HTTP %d: %s. Requested: %s, Allowed: %s",
							statusCode, StatusCode.fromStatusCode(statusCode).get().getReasonPhrase(), request.getHttpMethod().name(),
							String.join(", ", allowedHttpMethodsAsStrings)).getBytes(getCharset()))
					.build();
		}

		if (postProcessor != null)
			marshaledResponse = postProcessor.postProcess(marshaledResponse);

		return marshaledResponse;
	}

	@Nonnull
	@Override
	public MarshaledResponse forContentTooLarge(@Nonnull Request request,
																							@Nullable ResourceMethod resourceMethod) {
		requireNonNull(request);

		ContentTooLargeHandler contentTooLargeHandler = getContentTooLargeHandler().orElse(null);
		PostProcessor postProcessor = getPostProcessor().orElse(null);
		MarshaledResponse marshaledResponse;

		if (contentTooLargeHandler != null) {
			marshaledResponse = contentTooLargeHandler.handle(request, resourceMethod);
		} else {
			Integer statusCode = 413;

			marshaledResponse = MarshaledResponse.withStatusCode(statusCode)
					.headers(Map.of("Content-Type", Set.of(format("text/plain; charset=%s", getCharset().name()))))
					.body(format("HTTP %d: %s", statusCode, StatusCode.fromStatusCode(statusCode).get().getReasonPhrase()).getBytes(getCharset()))
					.build();
		}

		if (postProcessor != null)
			marshaledResponse = postProcessor.postProcess(marshaledResponse);

		return marshaledResponse;
	}

	@Nonnull
	@Override
	public MarshaledResponse forOptions(@Nonnull Request request,
																			@Nonnull Set<HttpMethod> allowedHttpMethods) {
		requireNonNull(request);
		requireNonNull(allowedHttpMethods);

		OptionsHandler optionsHandler = getOptionsHandler().orElse(null);
		PostProcessor postProcessor = getPostProcessor().orElse(null);
		MarshaledResponse marshaledResponse;

		if (optionsHandler != null) {
			marshaledResponse = optionsHandler.handle(request, allowedHttpMethods);
		} else {
			SortedSet<String> allowedHttpMethodsAsStrings = new TreeSet<>(allowedHttpMethods.stream()
					.map(httpMethod -> httpMethod.name())
					.collect(Collectors.toSet()));

			marshaledResponse = MarshaledResponse.withStatusCode(204)
					.headers(Map.of("Allow", allowedHttpMethodsAsStrings))
					.build();
		}

		if (postProcessor != null)
			marshaledResponse = postProcessor.postProcess(marshaledResponse);

		return marshaledResponse;
	}

	@Nonnull
	@Override
	public MarshaledResponse forHead(@Nonnull Request request,
																	 @Nonnull MarshaledResponse getMethodMarshaledResponse) {
		requireNonNull(request);
		requireNonNull(getMethodMarshaledResponse);

		HeadHandler headHandler = getHeadHandler().orElse(null);
		PostProcessor postProcessor = getPostProcessor().orElse(null);
		MarshaledResponse marshaledResponse;

		if (headHandler != null) {
			marshaledResponse = headHandler.handle(request, getMethodMarshaledResponse);
		} else {
			// A HEAD can never write a response body, but we explicitly set its Content-Length header
			// so the client knows how long the response would have been.
			marshaledResponse = getMethodMarshaledResponse.copy()
					.body(null)
					.headers((mutableHeaders) -> {
						byte[] responseBytes = getMethodMarshaledResponse.getBody().orElse(emptyByteArray());
						mutableHeaders.put("Content-Length", Set.of(String.valueOf(responseBytes.length)));
					}).finish();
		}

		if (postProcessor != null)
			marshaledResponse = postProcessor.postProcess(marshaledResponse);

		return marshaledResponse;
	}

	@Nonnull
	@Override
	public MarshaledResponse forThrowable(@Nonnull Request request,
																				@Nonnull Throwable throwable,
																				@Nullable ResourceMethod resourceMethod) {
		requireNonNull(request);
		requireNonNull(throwable);

		ThrowableHandler throwableHandler = getThrowableHandler().orElse(null);
		PostProcessor postProcessor = getPostProcessor().orElse(null);
		MarshaledResponse marshaledResponse;

		if (throwableHandler != null) {
			marshaledResponse = throwableHandler.handle(request, throwable, resourceMethod);
		} else {
			Integer statusCode = throwable instanceof BadRequestException ? 400 : 500;

			marshaledResponse = MarshaledResponse.withStatusCode(statusCode)
					.headers(Map.of("Content-Type", Set.of(format("text/plain; charset=%s", getCharset().name()))))
					.body(format("HTTP %d: %s", statusCode, StatusCode.fromStatusCode(statusCode).get().getReasonPhrase()).getBytes(getCharset()))
					.build();
		}

		if (postProcessor != null)
			marshaledResponse = postProcessor.postProcess(marshaledResponse);

		return marshaledResponse;
	}

	@Nonnull
	@Override
	public MarshaledResponse forCorsPreflightAllowed(@Nonnull Request request,
																									 @Nonnull CorsPreflight corsPreflight,
																									 @Nonnull CorsPreflightResponse corsPreflightResponse) {
		requireNonNull(request);
		requireNonNull(corsPreflight);
		requireNonNull(corsPreflightResponse);

		CorsPreflightAllowedHandler corsPreflightAllowedHandler = getCorsPreflightAllowedHandler().orElse(null);
		PostProcessor postProcessor = getPostProcessor().orElse(null);
		MarshaledResponse marshaledResponse;

		if (corsPreflightAllowedHandler != null) {
			marshaledResponse = corsPreflightAllowedHandler.handle(request, corsPreflight, corsPreflightResponse);
		} else {
			Integer statusCode = 204;
			Map<String, Set<String>> headers = new LinkedHashMap<>();

			Boolean accessControlAllowCredentials = corsPreflightResponse.getAccessControlAllowCredentials().orElse(null);

			String normalizedAccessControlAllowOrigin = normalizedAccessControlAllowOrigin(
					corsPreflight.getOrigin(),
					corsPreflightResponse.getAccessControlAllowOrigin(),
					accessControlAllowCredentials
			);

			headers.put("Access-Control-Allow-Origin", Set.of(normalizedAccessControlAllowOrigin));

			if (Boolean.TRUE.equals(accessControlAllowCredentials))
				headers.put("Access-Control-Allow-Credentials", Set.of("true"));

			// Always add Vary: Origin for specific origins (not "*").
			// For preflight, also vary on inputs that affect the decision.
			if (!"*".equals(normalizedAccessControlAllowOrigin)) {
				Set<String> vary = new LinkedHashSet<>(headers.getOrDefault("Vary", new LinkedHashSet<>()));
				vary.add("Origin");
				vary.add("Access-Control-Request-Method");
				vary.add("Access-Control-Request-Headers");
				headers.put("Vary", vary);
			}

			Set<String> accessControlAllowHeaders = corsPreflightResponse.getAccessControlAllowHeaders();

			if (!accessControlAllowHeaders.isEmpty())
				headers.put("Access-Control-Allow-Headers", new LinkedHashSet<>(accessControlAllowHeaders));

			Set<String> allowMethodStrings = new LinkedHashSet<>();

			for (HttpMethod httpMethod : corsPreflightResponse.getAccessControlAllowMethods())
				allowMethodStrings.add(httpMethod.name());

			if (!allowMethodStrings.isEmpty())
				headers.put("Access-Control-Allow-Methods", allowMethodStrings);

			Duration accessControlMaxAge = corsPreflightResponse.getAccessControlMaxAge().orElse(null);

			if (accessControlMaxAge != null && !accessControlMaxAge.isNegative() && !accessControlMaxAge.isZero())
				headers.put("Access-Control-Max-Age", Set.of(Long.toString(accessControlMaxAge.toSeconds())));

			marshaledResponse = MarshaledResponse.withStatusCode(statusCode)
					.headers(headers)
					.build();
		}

		if (postProcessor != null)
			marshaledResponse = postProcessor.postProcess(marshaledResponse);

		return marshaledResponse;
	}

	@Nonnull
	@Override
	public MarshaledResponse forCorsPreflightRejected(@Nonnull Request request,
																										@Nonnull CorsPreflight corsPreflight) {
		requireNonNull(request);
		requireNonNull(corsPreflight);

		CorsPreflightRejectedHandler corsPreflightRejectedHandler = getCorsPreflightRejectedHandler().orElse(null);
		PostProcessor postProcessor = getPostProcessor().orElse(null);
		MarshaledResponse marshaledResponse;

		if (corsPreflightRejectedHandler != null) {
			marshaledResponse = corsPreflightRejectedHandler.handle(request, corsPreflight);
		} else {
			Integer statusCode = 403;

			marshaledResponse = MarshaledResponse.withStatusCode(statusCode)
					.headers(Map.of("Content-Type", Set.of(format("text/plain; charset=%s", getCharset().name()))))
					.body(format("HTTP %d: %s (CORS preflight rejected)", statusCode,
							StatusCode.fromStatusCode(statusCode).get().getReasonPhrase()).getBytes(getCharset()))
					.build();
		}

		if (postProcessor != null)
			marshaledResponse = postProcessor.postProcess(marshaledResponse);

		return marshaledResponse;
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

		CorsAllowedHandler corsAllowedHandler = getCorsAllowedHandler().orElse(null);
		PostProcessor postProcessor = getPostProcessor().orElse(null);
		MarshaledResponse finalMarshaledResponse;

		if (corsAllowedHandler != null) {
			finalMarshaledResponse = corsAllowedHandler.handle(request, cors, corsResponse, marshaledResponse);
		} else {
			// Mutate a copy of the downstream headers
			Map<String, Set<String>> mutableHeaders = new LinkedHashMap<>(marshaledResponse.getHeaders());

			Boolean accessControlAllowCredentials = corsResponse.getAccessControlAllowCredentials().orElse(null);

			String normalizedAccessControlAllowOrigin = normalizedAccessControlAllowOrigin(
					cors.getOrigin(),
					corsResponse.getAccessControlAllowOrigin(),
					accessControlAllowCredentials
			);

			mutableHeaders.put("Access-Control-Allow-Origin", Set.of(normalizedAccessControlAllowOrigin));

			// Either "true" or omit entirely
			if (Boolean.TRUE.equals(accessControlAllowCredentials))
				mutableHeaders.put("Access-Control-Allow-Credentials", Set.of("true"));

			// Always add Vary: Origin for specific origins (not "*"), and preserve any existing Vary values
			if (!"*".equals(normalizedAccessControlAllowOrigin)) {
				Set<String> vary = new LinkedHashSet<>(mutableHeaders.getOrDefault("Vary", new LinkedHashSet<>()));
				vary.add("Origin");
				mutableHeaders.put("Vary", vary);
			}

			Set<String> accessControlExposeHeaders = corsResponse.getAccessControlExposeHeaders();

			if (!accessControlExposeHeaders.isEmpty())
				mutableHeaders.put("Access-Control-Expose-Headers", new LinkedHashSet<>(accessControlExposeHeaders));

			finalMarshaledResponse = marshaledResponse.copy()
					.headers(mutableHeaders)
					.finish();
		}

		if (postProcessor != null)
			finalMarshaledResponse = postProcessor.postProcess(finalMarshaledResponse);

		return finalMarshaledResponse;
	}

	@Nonnull
	private String normalizedAccessControlAllowOrigin(@Nonnull String origin,
																										@Nonnull String accessControlAllowOrigin,
																										@Nullable Boolean accessControlAllowCredentials) {
		requireNonNull(origin);
		requireNonNull(accessControlAllowOrigin);

		// If credentials are allowed, "*" is forbidden and must echo the request Origin
		if (Objects.equals(Boolean.TRUE, accessControlAllowCredentials) && "*".equals(trimAggressivelyToEmpty(accessControlAllowOrigin)))
			return origin;

		return accessControlAllowOrigin;
	}

	@Nonnull
	protected Charset getCharset() {
		return this.charset;
	}

	@Nonnull
	protected Optional<ResourceMethodHandler> getResourceMethodHandler() {
		return Optional.ofNullable(this.resourceMethodHandler);
	}

	@Nonnull
	protected Optional<NotFoundHandler> getNotFoundHandler() {
		return Optional.ofNullable(this.notFoundHandler);
	}

	@Nonnull
	protected Optional<MethodNotAllowedHandler> getMethodNotAllowedHandler() {
		return Optional.ofNullable(this.methodNotAllowedHandler);
	}

	@Nonnull
	protected Optional<ContentTooLargeHandler> getContentTooLargeHandler() {
		return Optional.ofNullable(this.contentTooLargeHandler);
	}

	@Nonnull
	protected Optional<OptionsHandler> getOptionsHandler() {
		return Optional.ofNullable(this.optionsHandler);
	}

	@Nonnull
	protected Optional<ThrowableHandler> getThrowableHandler() {
		return Optional.ofNullable(this.throwableHandler);
	}

	@Nonnull
	protected Optional<HeadHandler> getHeadHandler() {
		return Optional.ofNullable(this.headHandler);
	}

	@Nonnull
	protected Optional<CorsPreflightAllowedHandler> getCorsPreflightAllowedHandler() {
		return Optional.ofNullable(this.corsPreflightAllowedHandler);
	}

	@Nonnull
	protected Optional<CorsPreflightRejectedHandler> getCorsPreflightRejectedHandler() {
		return Optional.ofNullable(this.corsPreflightRejectedHandler);
	}

	@Nonnull
	protected Optional<CorsAllowedHandler> getCorsAllowedHandler() {
		return Optional.ofNullable(this.corsAllowedHandler);
	}

	@Nonnull
	protected Optional<PostProcessor> getPostProcessor() {
		return Optional.ofNullable(this.postProcessor);
	}
}
