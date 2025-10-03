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
 *   <li>{@link #withWhitelistAuthorizer(Function)} (permit origins via function)</li>
 * </ul>
 * See <a href="https://www.soklet.com/docs/cors#authorizing-cors-requests">https://www.soklet.com/docs/cors#authorizing-cors-requests</a> for detailed documentation.
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
	@Nonnull
	Optional<CorsResponse> authorize(@Nonnull Request request,
																	 @Nonnull Cors cors);

	/**
	 * Authorizes a <a href="https://developer.mozilla.org/en-US/docs/Glossary/Preflight_request">CORS preflight</a> request.
	 *
	 * @param request                              the preflight request to authorize
	 * @param corsPreflight                        the CORS preflight data provided in the request
	 * @param availableResourceMethodsByHttpMethod <em>Resource Methods</em> that are available to serve requests according to parameters specified by the preflight data
	 * @return a {@link CorsPreflightResponse} if authorized, or {@link Optional#empty()} if not authorized
	 */
	@Nonnull
	Optional<CorsPreflightResponse> authorizePreflight(@Nonnull Request request,
																										 @Nonnull CorsPreflight corsPreflight,
																										 @Nonnull Map<HttpMethod, ResourceMethod> availableResourceMethodsByHttpMethod);

	/**
	 * Acquires a threadsafe {@link CorsAuthorizer} configured to permit all cross-domain requests <strong>regardless of {@code Origin}</strong>.
	 * <p>
	 * The returned instance is guaranteed to be a JVM-wide singleton.
	 * <p>
	 * <strong>Note: the returned instance is generally unsafe for production - prefer {@link #withWhitelistedOrigins(Set)} or {@link #withWhitelistAuthorizer(Function)} for production systems.</strong>
	 *
	 * @return a {@code CorsAuthorizer} configured to permit all cross-domain requests
	 */
	@Nonnull
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
	@Nonnull
	static CorsAuthorizer withRejectAllPolicy() {
		return NoOriginsCorsAuthorizer.defaultInstance();
	}

	/**
	 * Acquires a threadsafe {@link CorsAuthorizer} configured to accept only those cross-domain requests whose {@code Origin} matches a value in the provided set of {@code whitelistedOrigins}.
	 * <p>
	 * Callers should not rely on reference identity; this method may return a new or cached instance.
	 *
	 * @return a {@code CorsAuthorizer} configured to accept only the specified {@code whitelistedOrigins}
	 */
	@Nonnull
	static CorsAuthorizer withWhitelistedOrigins(@Nonnull Set<String> whitelistedOrigins) {
		requireNonNull(whitelistedOrigins);
		return WhitelistedOriginsCorsAuthorizer.withOrigins(whitelistedOrigins);
	}

	/**
	 * Acquires a threadsafe {@link CorsAuthorizer} configured to accept only those cross-domain requests whose {@code Origin} is allowed by the provided {@code whitelistAuthorizer} function.
	 * <p>
	 * The {@code whitelistAuthorizer} function should return {@code true} if the supplied {@code Origin} is acceptable and {@code false} otherwise.
	 * <p>
	 * Callers should not rely on reference identity; this method may return a new or cached instance.
	 *
	 * @return a {@code CorsAuthorizer} configured to accept only the specified {@code whitelistedOrigins}
	 */
	@Nonnull
	static CorsAuthorizer withWhitelistAuthorizer(@Nonnull Function<String, Boolean> whitelistAuthorizer) {
		requireNonNull(whitelistAuthorizer);
		return WhitelistedOriginsCorsAuthorizer.withAuthorizer(whitelistAuthorizer);
	}
}
