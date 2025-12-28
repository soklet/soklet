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

import org.jspecify.annotations.NonNull;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * Contract for types that authorize <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS">CORS</a> requests.
 * <p>
 * Standard threadsafe implementations can be acquired via these factory methods:
 * <ul>
 *   <li>{@link #withRejectAllPolicy()} (don't permit CORS requests)</li>
 *   <li>{@link #withAcceptAllPolicy()} (permit all CORS requests, not recommended for production)</li>
 *   <li>{@link #withWhitelistedOrigins(Set)} (permit whitelisted origins only)</li>
 *   <li>{@link #withWhitelistedOrigins(Set, Function)}  (permit whitelisted origins only + control credentials behavior)</li>
 *   <li>{@link #withWhitelistAuthorizer(Function)} (permit origins via function)</li>
 *   <li>{@link #withWhitelistAuthorizer(Function, Function)} (permit origins via function + control credentials behavior)</li>
 * </ul>
 * See <a href="https://www.soklet.com/docs/cors">https://www.soklet.com/docs/cors</a> for detailed documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface CorsAuthorizer {
	/**
	 * Authorizes a <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS">non-preflight CORS</a> request.
	 *
	 * @param request the request to authorize
	 * @param cors    the CORS data provided in the request
	 * @return a {@link CorsResponse} if authorized, or {@link Optional#empty()} if not authorized
	 */
	@NonNull
	Optional<CorsResponse> authorize(@NonNull Request request,
																						@NonNull Cors cors);

	/**
	 * Authorizes a <a href="https://developer.mozilla.org/en-US/docs/Glossary/Preflight_request">CORS preflight</a> request.
	 *
	 * @param request                              the preflight request to authorize
	 * @param corsPreflight                        the CORS preflight data provided in the request
	 * @param availableResourceMethodsByHttpMethod <em>Resource Methods</em> that are available to serve requests according to parameters specified by the preflight data
	 * @return a {@link CorsPreflightResponse} if authorized, or {@link Optional#empty()} if not authorized
	 */
	@NonNull
	Optional<CorsPreflightResponse> authorizePreflight(@NonNull Request request,
																															@NonNull CorsPreflight corsPreflight,
																															@NonNull Map<@NonNull HttpMethod, @NonNull ResourceMethod> availableResourceMethodsByHttpMethod);

	/**
	 * Acquires a threadsafe {@link CorsAuthorizer} configured to permit all cross-domain requests <strong>regardless of {@code Origin}</strong>.
	 * <p>
	 * The returned instance is guaranteed to be a JVM-wide singleton.
	 * <p>
	 * <strong>Note: the returned instance is generally unsafe for production - prefer {@link #withWhitelistedOrigins(Set)} or {@link #withWhitelistAuthorizer(Function)} for production systems.</strong>
	 *
	 * @return a {@code CorsAuthorizer} configured to permit all cross-domain requests
	 */
	@NonNull
	static CorsAuthorizer withAcceptAllPolicy() {
		return AllOriginsCorsAuthorizer.defaultInstance();
	}

	/**
	 * Acquires a threadsafe {@link CorsAuthorizer} configured to reject all cross-domain requests <strong>regardless of {@code Origin}</strong>.
	 * <p>
	 * The returned instance is guaranteed to be a JVM-wide singleton.
	 *
	 * @return a {@code CorsAuthorizer} configured to reject all cross-domain requests
	 */
	@NonNull
	static CorsAuthorizer withRejectAllPolicy() {
		return NoOriginsCorsAuthorizer.defaultInstance();
	}

	/**
	 * Acquires a threadsafe {@link CorsAuthorizer} configured to accept only those cross-domain requests whose {@code Origin} matches a value in the provided set of {@code whitelistedOrigins}.
	 * <p>
	 * The returned {@link CorsAuthorizer} will set {@code Access-Control-Allow-Credentials} header to {@code true}. This behavior can be customized via {@link #withWhitelistedOrigins(Set, Function)}.
	 * <p>
	 * Callers should not rely on reference identity; this method may return a new or cached instance.
	 *
	 * @param whitelistedOrigins the set of whitelisted origins
	 * @return a credentials-allowed {@code CorsAuthorizer} configured to accept only the specified {@code whitelistedOrigins}
	 */
	@NonNull
	static CorsAuthorizer withWhitelistedOrigins(@NonNull Set<@NonNull String> whitelistedOrigins) {
		requireNonNull(whitelistedOrigins);
		return WhitelistedOriginsCorsAuthorizer.withOrigins(whitelistedOrigins, (origin) -> false);
	}

	/**
	 * Acquires a threadsafe {@link CorsAuthorizer} configured to accept only those cross-domain requests whose {@code Origin} matches a value in the provided set of {@code whitelistedOrigins}.
	 * <p>
	 * The provided {@code allowCredentialsResolver} is used to control the value of {@code Access-Control-Allow-Credentials}: it's a function which takes a normalized {@code Origin} as input and should return {@code true} if clients are permitted to include credentials in cross-origin HTTP requests and {@code false} otherwise.
	 * <p>
	 * The returned {@link CorsAuthorizer} will omit the {@code Access-Control-Allow-Credentials} response header to reduce CSRF attack surface area. This behavior can be customized via {@link #withWhitelistAuthorizer(Function, Function)}.
	 * <p>
	 * Callers should not rely on reference identity; this method may return a new or cached instance.
	 *
	 * @param whitelistedOrigins       the set of whitelisted origins
	 * @param allowCredentialsResolver function which takes a normalized {@code Origin} as input and should return {@code true} if clients are permitted to include credentials in cross-origin HTTP requests and {@code false} otherwise
	 * @return a {@code CorsAuthorizer} configured to accept only the specified {@code whitelistedOrigins}, with {@code allowCredentialsResolver} dictating whether credentials are allowed
	 */
	@NonNull
	static CorsAuthorizer withWhitelistedOrigins(@NonNull Set<@NonNull String> whitelistedOrigins,
																							 @NonNull Function<String, Boolean> allowCredentialsResolver) {
		requireNonNull(whitelistedOrigins);
		requireNonNull(allowCredentialsResolver);

		return WhitelistedOriginsCorsAuthorizer.withOrigins(whitelistedOrigins, allowCredentialsResolver);
	}

	/**
	 * Acquires a threadsafe {@link CorsAuthorizer} configured to accept only those cross-domain requests whose {@code Origin} is allowed by the provided {@code whitelistAuthorizer} function.
	 * <p>
	 * The {@code whitelistAuthorizer} function should return {@code true} if the supplied {@code Origin} is acceptable and {@code false} otherwise.
	 * <p>
	 * The returned {@link CorsAuthorizer} will omit the {@code Access-Control-Allow-Credentials} response header to reduce CSRF attack surface area. This behavior can be customized via {@link #withWhitelistAuthorizer(Function, Function)}.
	 * <p>
	 * Callers should not rely on reference identity; this method may return a new or cached instance.
	 *
	 * @param whitelistAuthorizer a function that returns {@code true} if the input is a whitelisted origin and {@code false} otherwise
	 * @return a credentials-allowed {@code CorsAuthorizer} configured to accept only the origins permitted by {@code whitelistAuthorizer}
	 */
	@NonNull
	static CorsAuthorizer withWhitelistAuthorizer(@NonNull Function<String, Boolean> whitelistAuthorizer) {
		requireNonNull(whitelistAuthorizer);
		return WhitelistedOriginsCorsAuthorizer.withAuthorizer(whitelistAuthorizer, (origin) -> false);
	}

	/**
	 * Acquires a threadsafe {@link CorsAuthorizer} configured to accept only those cross-domain requests whose {@code Origin} is allowed by the provided {@code whitelistAuthorizer} function.
	 * <p>
	 * The {@code whitelistAuthorizer} function should return {@code true} if the supplied {@code Origin} is acceptable and {@code false} otherwise.
	 * <p>
	 * The provided {@code allowCredentialsResolver} is used to control the value of {@code Access-Control-Allow-Credentials}: it's a function which takes a normalized {@code Origin} as input and should return {@code true} if clients are permitted to include credentials in cross-origin HTTP requests and {@code false} otherwise.
	 * <p>
	 * Callers should not rely on reference identity; this method may return a new or cached instance.
	 *
	 * @param whitelistAuthorizer      a function that returns {@code true} if the input is a whitelisted origin and {@code false} otherwise
	 * @param allowCredentialsResolver function which takes a normalized {@code Origin} as input and should return {@code true} if clients are permitted to include credentials in cross-origin HTTP requests and {@code false} otherwise
	 * @return a {@code CorsAuthorizer} configured to accept only the origins permitted by {@code whitelistAuthorizer}, with {@code allowCredentialsResolver} dictating whether credentials are allowed
	 */
	@NonNull
	static CorsAuthorizer withWhitelistAuthorizer(@NonNull Function<String, Boolean> whitelistAuthorizer,
																								@NonNull Function<String, Boolean> allowCredentialsResolver) {
		requireNonNull(whitelistAuthorizer);
		requireNonNull(allowCredentialsResolver);

		return WhitelistedOriginsCorsAuthorizer.withAuthorizer(whitelistAuthorizer, allowCredentialsResolver);
	}
}
