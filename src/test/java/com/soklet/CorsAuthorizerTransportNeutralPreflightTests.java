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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorsAuthorizerTransportNeutralPreflightTests {
	private static final String ALLOWED_ORIGIN = "https://allowed.example";
	private static final String REJECTED_ORIGIN = "https://rejected.example";
	private static final Set<HttpMethod> AVAILABLE_HTTP_METHODS = Set.of(HttpMethod.POST, HttpMethod.OPTIONS);

	@Test
	void customAuthorizersSecurelyRejectTransportNeutralPreflightByDefault() {
		CorsAuthorizer authorizer = new CorsAuthorizer() {
			@NonNull
			@Override
			public Optional<CorsResponse> authorize(@NonNull Request request,
																						@NonNull Cors cors) {
				return Optional.of(CorsResponse.withAccessControlAllowOrigin(cors.getOrigin()).build());
			}

			@NonNull
			@Override
			public Optional<CorsPreflightResponse> authorizePreflight(@NonNull Request request,
																															@NonNull CorsPreflight corsPreflight,
																															@NonNull Map<@NonNull HttpMethod, @NonNull ResourceMethod> availableResourceMethodsByHttpMethod) {
				return Optional.of(CorsPreflightResponse.withAccessControlAllowOrigin(corsPreflight.getOrigin()).build());
			}
		};

		assertTrue(authorizer.authorizePreflight(request(), preflight(ALLOWED_ORIGIN), AVAILABLE_HTTP_METHODS).isEmpty());
	}

	@Test
	void acceptAllAuthorizerSupportsTransportNeutralPreflight() {
		CorsPreflightResponse response = CorsAuthorizer.acceptAllInstance()
				.authorizePreflight(request(), preflight(ALLOWED_ORIGIN), AVAILABLE_HTTP_METHODS)
				.orElseThrow();

		assertEquals(ALLOWED_ORIGIN, response.getAccessControlAllowOrigin());
		assertEquals(AVAILABLE_HTTP_METHODS, response.getAccessControlAllowMethods());
		assertEquals(Set.of("Authorization", "X-Request-Id"), response.getAccessControlAllowHeaders());
		assertEquals(Optional.of(true), response.getAccessControlAllowCredentials());
		assertEquals(Optional.of(Duration.ofMinutes(10)), response.getAccessControlMaxAge());
	}

	@Test
	void rejectAllAuthorizerRejectsTransportNeutralPreflight() {
		assertTrue(CorsAuthorizer.rejectAllInstance()
				.authorizePreflight(request(), preflight(ALLOWED_ORIGIN), AVAILABLE_HTTP_METHODS)
				.isEmpty());
	}

	@Test
	void whitelistedAuthorizerSupportsTransportNeutralPreflightForAllowedOriginOnly() {
		CorsAuthorizer authorizer = CorsAuthorizer.fromWhitelistedOrigins(Set.of(ALLOWED_ORIGIN));
		CorsPreflightResponse response = authorizer
				.authorizePreflight(request(), preflight(ALLOWED_ORIGIN), AVAILABLE_HTTP_METHODS)
				.orElseThrow();

		assertEquals(ALLOWED_ORIGIN, response.getAccessControlAllowOrigin());
		assertEquals(AVAILABLE_HTTP_METHODS, response.getAccessControlAllowMethods());
		assertEquals(Set.of("Authorization", "X-Request-Id"), response.getAccessControlAllowHeaders());
		assertEquals(Optional.of(false), response.getAccessControlAllowCredentials());
		assertEquals(Optional.of(Duration.ofMinutes(10)), response.getAccessControlMaxAge());
		assertTrue(authorizer.authorizePreflight(request(), preflight(REJECTED_ORIGIN), AVAILABLE_HTTP_METHODS).isEmpty());
	}

	@NonNull
	private static Request request() {
		return Request.withPath(HttpMethod.OPTIONS, "/mcp").build();
	}

	@NonNull
	private static CorsPreflight preflight(@NonNull String origin) {
		return CorsPreflight.with(origin, HttpMethod.POST, Set.of("Authorization", "X-Request-Id"));
	}
}
